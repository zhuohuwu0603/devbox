package devbox.agent

import java.io.{DataInputStream, DataOutputStream}
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption

import devbox.common.Cli
import devbox.common.Cli.Arg
import devbox.common._
import Cli.pathScoptRead
object DevboxAgentMain {
  case class Config(logFile: Option[os.Path] = None,
                    help: Boolean = false,
                    ignoreStrategy: String = "",
                    workingDir: String = "",
                    exitOnError: Boolean = false)

  def main(args: Array[String]): Unit = {
    val signature = Seq(
      Arg[Config, Unit](
        "help", None,
        "Print this message",
        (c, v) => c.copy(help = true)
      ),
      Arg[Config, String](
        "ignore-strategy", None,
        "",
        (c, v) => c.copy(ignoreStrategy = v)
      ),
      Arg[Config, String](
        "working-dir", None,
        "",
        (c, v) => c.copy(workingDir = v)
      ),
      Arg[Config, Unit](
        "exit-on-error", None,
        "",
        (c, v) => c.copy(exitOnError = true)
      )
    )

    Cli.groupArgs(args.toList, signature, Config()) match {
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)

      case Right((config, remaining)) =>
        val logger = Logger.JsonStderr

        logger("AGNT START", config.workingDir)

        val skipper = Util.ignoreCallback(config.ignoreStrategy)
        val client = new RpcClient(
          new DataOutputStream(System.out),
          new DataInputStream(System.in),
          (tag, t) => logger("AGNT " + tag, t)
        )
        mainLoop(logger, skipper, client, os.Path(config.workingDir, os.pwd), config.exitOnError)
    }
  }
  def mainLoop(logger: Logger,
               skipper: Skipper,
               client: RpcClient,
               wd: os.Path,
               exitOnError: Boolean) = {


    val buffer = new Array[Byte](Util.blockSize)
    while (true) try client.readMsg[Rpc]() match {
      case Rpc.FullScan(path) =>
        val scanRoot = os.Path(path, wd)
        val skip = skipper.initialize(scanRoot)
        for {
          p <- os.walk.stream(scanRoot, p => skip(p) && ! os.isDir(p, followLinks = false))
          sig <- Signature.compute(p, buffer)
        } {
          client.writeMsg(Some((p.relativeTo(scanRoot).toString, sig)))
        }

        client.writeMsg(None)

      case Rpc.Remove(root, path) =>
        os.remove.all(os.Path(path, wd / root))
        client.writeMsg(0)

      case Rpc.PutFile(root, path, perms) =>
        os.write(os.Path(path, wd / root), "", perms)
        client.writeMsg(0)

      case Rpc.PutDir(root, path, perms) =>
        os.makeDir(os.Path(path, wd / root), perms)
        client.writeMsg(0)

      case Rpc.PutLink(root, path, dest) =>
        os.symlink(os.Path(path, wd / root), os.FilePath(dest))
        client.writeMsg(0)

      case Rpc.WriteChunk(root, path, offset, data, hash) =>
        val p = os.Path(path, wd / root)
        withWritable(p){
          os.write.write(p, data.value, Seq(StandardOpenOption.WRITE), 0, offset)
        }
        client.writeMsg(0)

      case Rpc.SetSize(root, path, offset) =>
        val p = os.Path(path, wd / root)
        withWritable(p) {
          os.truncate(p, offset)
        }
        client.writeMsg(0)

      case Rpc.SetPerms(root, path, perms) =>
        os.perms.set.apply(os.Path(path, wd / root), perms)
        client.writeMsg(0)
    }catch{case e: Throwable if !exitOnError =>
      logger("AGNT ERROR", e)
      client.writeMsg(RemoteException.create(e), false)
    }
  }
  def withWritable[T](p: os.Path)(t: => T) = {
    val perms = os.perms(p)
    if (perms.contains(PosixFilePermission.OWNER_WRITE)) {
      t
    }else{
      os.perms.set(p, perms + PosixFilePermission.OWNER_WRITE)
      val res = t
      os.perms.set(p, perms)
      res
    }
  }
}