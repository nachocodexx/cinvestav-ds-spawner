package mx.cinvestav

import cats.Show
import cats.data.Chain
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import fs2.io.readInputStream
import fs2.{Stream, text}
import mx.cinvestav.config.DefaultConfig

import scala.concurrent.duration._
import scala.language.postfixOps
import pureconfig._
import pureconfig.generic.auto._

object Application extends  IOApp{
  case class DockerRun(name:String,port:Int,image:String)
  case class DockerStop(containerId:String)
  implicit val showDockerRun:Show[DockerRun]=
    Show.show(x=>s"docker run " +
      s"--name ${x.name} "+
      s"--env PORT=${x.port} " +
      s"--env HOST=localhost " +
      s"--env NAME=${x.name} " +
      s"-l worker=${x.name} " +
      s"-d "+
      s"-p ${x.port}:${x.port} "+
      s"${x.image}"
    )
  implicit val showDockerStop:Show[DockerStop]= Show.show(x=>s"docker rm -f ${x.containerId}")

  def removeAndStop(toRemovedContainers:List[String])(implicit ioRt:IO[Runtime]): Stream[IO, Process] = Stream
    .emits(toRemovedContainers)
    .covary[IO]
    .debug(x=>s"REMOVED WORKER CONTAINER $x")
    .fmap(DockerStop(_).show)
    .evalMap(cmd=>ioRt.map(_.exec(cmd)))
    .metered(0.5 seconds)

  def spawnContainers(cIdsLen:Int,leftContainers:Int)(implicit config:DefaultConfig,ioRt:IO[Runtime]): Stream[IO, Process] =
    Stream
    .iterate(config.workersPort+cIdsLen)(_+1)
    .take(leftContainers)
    .debug(x=>s"SPAWN ${config.workersName}-${x-config.workersPort} ON PORT $x")
    .covary[IO]
    .fmap(port=>DockerRun(s"${config.workersName}-${port-config.workersPort}",port,"nachocode/cinvestav-ds-worker")
      .show)
    .evalMap(cmd=>ioRt.map(_.exec(cmd)))
    .metered(1.5 seconds)
  override def run(args: List[String]): IO[ExitCode] ={
    val config = ConfigSource.default.load[DefaultConfig]
    config match {
      case Left(value) =>
        println(value)
        IO.unit.as(ExitCode.Error)
      case Right(config) =>
        implicit  val _c = config
        println(s"Spawn ${config.workers} workers\nName: ${config.workersName}\nPort: ${config.workersPort} to " +
          s"${config.workersPort +config.workers}")
        implicit val ioRt = IO(Runtime.getRuntime)
        val ioOutputStream = ioRt.flatMap(_.exec("docker ps -f label=worker --format {{.ID}}")
          .pure[IO])
          .map(_.getInputStream)
        val app = Stream.eval(ioOutputStream)
          .map(_.pure[IO])
          .flatMap(readInputStream[IO](_,4096,closeAfterUse = true))
          .through(text.utf8Decode)
          .through(text.lines)
          .filter(x=>x.nonEmpty)
          .fold(Chain.empty[String])((x,y)=>x.append(y))
          .filter(x=>x.length != config.workers)
          .flatMap{ containersIds=>
            val cIdsLen             = containersIds.length
            val extraWorkers    = (cIdsLen-config.workers).toInt
            val leftContainers      = config.workers-cIdsLen
            val toRemovedContainers = containersIds.toList.slice(0,extraWorkers)

            if(cIdsLen>config.workers)  removeAndStop(toRemovedContainers)
            else spawnContainers(cIdsLen.toInt,leftContainers.toInt)
          }
          .compile
          .drain
        app.as(ExitCode.Success)

    }


  }
}