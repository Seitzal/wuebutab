package wuebutab

import java.io.{File, PrintWriter}
import scala.io.Source
import scala.util.{Try, Success, Failure}

import scala.jdk.CollectionConverters._

import com.google.api.services.sheets.v4._
import com.google.api.services.sheets.v4.model._

object Actions:

  given config: Config = Config.default

  private def getRemote: SpreadsheetHandle = 
    SpreadsheetHandle(Source.fromFile("remote").mkString) match
      case Success(sheet) => sheet
      case Failure(e) => 
        println("Error accessing remote. Please confirm that credentials and spreadsheet ID are correct.")
        throw(e)
  
  given SpreadsheetHandle = getRemote

  def setRemote(spreadsheetId: String): Unit = 
    SpreadsheetHandle(spreadsheetId) match
      case Success(sheet) => 
        val pw = PrintWriter("remote")
        pw.write(spreadsheetId)
        pw.close()
        println(s"Set remote to spreadsheet '${sheet.getTitle}' (https://docs.google.com/spreadsheets/d/$spreadsheetId)")
      case Failure(e) =>
        println("Error changing remote. Please confirm that credentials and spreadsheet ID are correct.")
        e.printStackTrace()
  
  def checkBallots(): Unit =
    val remote = getRemote
    val ballots = Ballot.fetchAll
    val rounds = Round.fetchAll
    val problems = Ballot.checkAll(ballots)
    val meta = TeamMeta.fetchAll
    println(s"${ballots.length} ballots checked, ${problems.length} problems found:")
    problems.foreach(println)
    println(Results(ballots, rounds, meta))

  def updateRankings(): Unit =
    val remote = getRemote
    val ballots = Ballot.fetchAll
    val problems = Ballot.checkAll(ballots)
    if !problems.isEmpty then
      println("Some ballots are problematic. Please address problems before pairing, or override with '-f'.")
      println(s"${problems.length} problems found:")
      problems.foreach(println)
    else
      val structure = Round.fetchAll
      val meta = TeamMeta.fetchAll
      val results = Results(ballots, structure, meta)
      println(results.teams.renderTable(Config.default.tableKeys.teams))
      Team.updateRemote(results.teams)

  def generatePairings(rounds: Option[List[Int]], update: Boolean): Unit =
    val remote = getRemote
    val ballots = Ballot.fetchAll
    val problems = Ballot.checkAll(ballots)
    if !problems.isEmpty then
      println("Some ballots are problematic. Please address problems before pairing, or override with '-f'.")
      println(s"${problems.length} problems found:")
      problems.foreach(println)
    else
      val structure = Round.fetchAll
      val meta = TeamMeta.fetchAll
      val results = Results(ballots, structure, meta)
      def iter(rounds_remaining: List[Int], teams: Vector[Team]): Unit =
        if !rounds_remaining.isEmpty then
          structure.find(_.roundNo == rounds_remaining.head) match
            case Some(round) =>
              val pairings = make_pairings(
                teams.filter(_.active), 
                round.debateType, 
                round.pairing.getOrElse(Weights.RANDOM),
                round.roundNo)
                .sortBy(p => (p.division, p.mean_rank_score)) 
              println(s"Pairings for round ${round.roundNo}:")
              println(pairings.renderTable(config.tableKeys.pairings))
              if update then Pairing.updateRemote(pairings, round.roundNo)
              iter(rounds_remaining.tail, teams.map(_.apply_pairings(pairings, round.roundNo)))
            case None => 
              println(s"Couldn't generate pairings for round ${rounds_remaining.head}: Not found in structure.")
              iter(rounds_remaining.tail, teams)
        else if update then
          Team.updateRemote(teams)
      rounds match
        case Some(rs) => iter(rs, results.teams) 
        case None => iter(List(results.rounds_completed.max + 1), results.teams)
  
  def generateSpeakerRanking(minRounds: Int): Unit =
    val remote = getRemote
    val ballots = Ballot.fetchAll
    val names = if remote.sheetExists("Names") then Names.fetch(remote, "Names") else Names.empty
    val speeches_old = if remote.sheetExists("Speeches") then Some(remote.readRange("Speeches")) else None
    val speeches = Speech.getAll(ballots, names, speeches_old)
    if !remote.sheetExists("Speeches") then remote.createSheet("Speeches")
    remote.writeRange("Speeches", speeches.asSeqTable)
    val newNames = names.update(speeches)
    if !remote.sheetExists("Names") then remote.createSheet("Names")
    remote.writeRange("Names", newNames.asSeqTable)
    val speakers = Speaker.getAll(newNames, speeches)
    val ranking = speakers.filter(_.roundsSpoken.length >= minRounds).sortBy(0 - _.average)
    if !remote.sheetExists("Speaker Ranking") then remote.createSheet("Speaker Ranking")
    remote.writeRange("Speaker Ranking", Vector("Rank") +: (1 to ranking.length).map(i => Vector(i.toString)))
    remote.writeRange("Speaker Ranking!B1", ranking.asSeqTable)
    println(ranking.renderTable)

  def allocateJudges(round: Int, update: Boolean): Unit =
    val remote = getRemote
    val ballots = Ballot.fetchAll
    val structure = Round.fetchAll
    val meta = TeamMeta.fetchAll
    val results = Results(ballots, structure, meta)
    val draws = Draw.fetchAll(remote)
    val judges = 
      Judge.fetchAll
        .map(_.updateForRounds(draws.values.toSeq))
    if !draws.isDefinedAt(round) then throw Error(s"No pairings for round $round found in remote.")
    else
      val draw = draws(round)
      val pairings = for row <- draw.tail yield
        val prop = results.teams.filter(_.name == row(1)).head
        val opp = results.teams.filter(_.name == row(2)).head
        Pairing(prop, opp, DebateType.Impromptu, 0d)
      val panels = make_panels(pairings, judges.filter(_.active), PanelWeights())
      println(panels.renderTable(config.tableKeys.panels))
      if update then Panel.updateRemote(panels, round)

  def test(): Unit =
    val remote = getRemote
    val ballots = Ballot.fetchAll
    val names = if remote.sheetExists("Names") then Names.fetch(remote, "Names") else Names.empty
    val speeches_old = if remote.sheetExists(config.sheetNames.speeches) then Some(remote.readRange(config.sheetNames.speeches)) else None
    val speeches = Speech.getAll(ballots, names, speeches_old)
    if !remote.sheetExists(config.sheetNames.speeches) then remote.createSheet(config.sheetNames.speeches)
    remote.writeRange(config.sheetNames.speeches, speeches.asSeqTable(config.tableKeys.speeches))
    
