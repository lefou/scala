import de.tototec.sbuild._
import de.tototec.sbuild.TargetRefs._
import de.tototec.sbuild.ant._
import de.tototec.sbuild.ant.tasks._
import java.io.File

@version("0.4.0.9000")
@classpath("mvn:org.apache.ant:ant:1.8.4")
class SBuild(implicit _project: Project) {

  Target("phony:all") dependsOn "locker-all" ~ "quick-all"

  Target("phony:clean").evictCache exec {
    AntDelete(dir = Path("target"))
  }

  val extraJavacArgs = Seq()
  val extraScalacArgs = Seq()
  val jvmArgs = Seq("-Xms1536M", "-Xmx1536M", "-Xss1M", "-XX:MaxPermSize=192M", "-XX:+UseParallelGC")

  val asmSources = """scan:src/asm;regex=.*\.java$"""
  val forkjoinSources = """scan:src/forkjoin;regex=.*\.java$"""

  val forkjoinJar = "target/forkjoin.jar"
  val ant = "mvn:org.apache.ant:ant:1.8.4"
  val jline = "lib/jline.jar"

  val stagesToSkipIfDirExists: Seq[String] = Prop("skipStages", "").split(",").filter(s => s.trim.length > 0)

  // TODO: directly use publically available jars
  val starrCompilerCp =
    "lib/scala-library.jar" ~
    "lib/scala-reflect.jar" ~
    "lib/scala-compiler.jar" ~
    forkjoinJar

  val asmClasses = Target("phony:compile-asm").cacheable dependsOn asmSources exec { ctx: TargetContext =>
    addons.java.Javac(
      sources = asmSources.files,
      destDir = Path("target/asm/classes"),
      target = "1.6", source = "1.5",
      additionalJavacArgs = extraJavacArgs ++ Seq("-XDignore.symbol.file")
    )
    ctx.attachFile(Path("target/asm/classes"))
  }

  Target("phony:compile-forkjoin").cacheable dependsOn forkjoinSources exec {
    addons.java.Javac(
      sources = forkjoinSources.files,
      destDir = Path("target/forkjoin/classes"),
      target = "1.6", source = "1.5",
      debugInfo = "all",
      additionalJavacArgs = extraJavacArgs ++ Seq("-XDignore.symbol.file")
    )
  }

  Target(forkjoinJar) dependsOn "compile-forkjoin" exec { ctx: TargetContext =>
    AntJar(destFile = ctx.targetFile.get, baseDir = Path("target/forkjoin/classes"))
  }

  def cleaner(producerName: String, dirToDelete: File): Target = {
    Target("phony:clean-"+producerName) evictCache producerName exec {
      AntDelete(dir = dirToDelete)
    }
  }

  // TODO: excludes
  def stagedJavac(stage: String, project: String,
                  outputDir: Option[String] = None,
                  args: Seq[String] = Seq(),
                  classpath: TargetRefs = TargetRefs(),
                  dependsOn: TargetRefs = TargetRefs()): Target = {
    val sources = s"scan:src/${project};regex=.*\\.java$$"
    val targetDir = outputDir match {
      case None => Path(s"target/${stage}/${project}/classes")
      case Some(dir) => Path(dir)
    }
    val t = Target(s"phony:compileJava-${stage}-${project}").cacheable dependsOn
      dependsOn ~ classpath ~ sources exec { ctx: TargetContext =>
      if(stagesToSkipIfDirExists.contains(stage) && targetDir.exists) {
        println(s"Skipping stage ${stage}")
      } else {
        addons.java.Javac(
          sources = sources.files,
          classpath = Seq(targetDir) ++ classpath.files,
          destDir = targetDir,
          target = "1.6", source = "1.5",
          additionalJavacArgs = extraJavacArgs ++ args
        )
      }
      ctx.attachFile(targetDir)
    }

    Target(s"phony:clean-${stage}") dependsOn cleaner(s"java-${stage}-${project}", targetDir)

    Target(s"phony:${stage}-all") dependsOn t
    t
  }

  def stagedScalac(stage: String, 
                   project: String,
                   outputDir: Option[String] = None, 
                   args: Seq[String] = Seq(),
                   classpath: TargetRefs = TargetRefs(),
                   compilerClasspath: TargetRefs,
                   dependsOn: TargetRefs = TargetRefs(),
                   sourcepath: Option[File] = None,
                   jvmArgs: Seq[String] = jvmArgs): Target = {

    val sources = s"scan:src/${project};regex=.*\\.scala$$"

    val targetDir = outputDir match {
      case None => Path(s"target/${stage}/${project}/classes")
      case Some(dir) => Path(dir)
    }

    val sourcePath = sourcepath match {
      case None => null
      case Some(path) => path
    }
    val t = Target(s"phony:compileScala-${stage}-${project}").cacheable dependsOn 
      compilerClasspath ~ dependsOn ~ classpath ~ sources exec { ctx: TargetContext =>
      if(stagesToSkipIfDirExists.contains(stage) && targetDir.exists) {
        println(s"Skipping stage ${stage}")
      } else {
        addons.scala.Scalac(
          sources = sources.files,
          classpath = Seq(targetDir) ++ classpath.files,
          sourcePath = sourcePath,
          fork = true,
          compilerClasspath = compilerClasspath.files,
          destDir = targetDir,
          target = "jvm-1.6",
          additionalScalacArgs = extraScalacArgs ++ args,
          useArgsFile = true,
          jvmArgs = jvmArgs
        )
      }
      ctx.attachFile(targetDir)
    }

    Target(s"phony:clean-${stage}") dependsOn cleaner(s"scala-${stage}-${project}", targetDir)

    Target(s"phony:${stage}-all") dependsOn t
    t
  }

  val lockerLibJavac = stagedJavac(stage = "locker", project = "library", args = Seq("-XDignore.symbol.file"),
    classpath = forkjoinJar
  )

  val lockerLib = stagedScalac(stage = "locker", project = "library", compilerClasspath = starrCompilerCp,
    classpath = forkjoinJar ~ ant ~ lockerLibJavac, sourcepath = Some(Path("src/library"))
  ) ~ lockerLibJavac

  val lockerReflect = stagedScalac(stage = "locker", project = "reflect", compilerClasspath = starrCompilerCp,
    classpath = forkjoinJar ~ ant ~ lockerLib)

  val lockerCompiler = stagedScalac(stage = "locker", project = "compiler", compilerClasspath = starrCompilerCp,
    classpath = forkjoinJar ~ asmClasses ~ ant ~ lockerLib ~ lockerReflect
  )

  val lockerCompilerCp = lockerLib ~ lockerReflect ~ lockerCompiler ~ asmClasses

  val quickLibJavac = stagedJavac(stage = "quick", project = "library", args = Seq("-XDignore.symbol.file"),
    classpath = forkjoinJar
  )
  val quickLib = stagedScalac(stage = "quick", project = "library", compilerClasspath = lockerCompilerCp, sourcepath = Some(Path("src/library")),
    classpath = forkjoinJar ~ ant ~ quickLibJavac
  ) ~ quickLibJavac

  val quickActorsJava = stagedJavac(stage = "quick", project = "actors", args = Seq("-XDignore.symbol.file"),
    // outputDir = Some("target/quick/library/classes"),
    classpath = forkjoinJar
  )
  val quickActors = stagedScalac(stage = "quick", project = "actors", compilerClasspath = lockerCompilerCp,
    // outputDir = Some("target/quick/library/classes"),
    classpath = forkjoinJar ~ quickLib ~ quickActorsJava
  ) ~ quickActorsJava

  val quickReflect = stagedScalac(stage = "quick", project = "reflect", compilerClasspath = lockerCompilerCp,
    classpath = forkjoinJar ~ ant ~ quickLib
  )

  val quickCompiler = stagedScalac(stage = "quick", project = "compiler", compilerClasspath = lockerCompilerCp,
    classpath = forkjoinJar ~ asmClasses ~ ant ~ quickLib ~ quickReflect
  )

  val quickReplJava = stagedJavac(stage = "quick", project = "repl",
    classpath = forkjoinJar ~ asmClasses ~ ant ~ quickLib ~ quickReflect ~ quickCompiler
  )
  val quickRepl = stagedScalac(stage = "quick", project = "repl", compilerClasspath = lockerCompilerCp,
    classpath = forkjoinJar ~ asmClasses ~ ant ~ jline ~ quickLib ~ quickReflect ~ quickCompiler ~ quickReplJava
  )

  val quickScalacheck = stagedScalac(stage = "quick", project = "scalacheck", compilerClasspath = lockerCompilerCp, args = Seq("-nowarn"),
    classpath =  forkjoinJar ~ asmClasses ~ quickLib ~ quickReflect ~ quickCompiler ~ quickActors
  )

  val quickScalap = stagedScalac(stage = "quick", project = "scalap", compilerClasspath = lockerCompilerCp,
    classpath =  forkjoinJar ~ asmClasses ~ quickLib ~ quickReflect ~ quickCompiler
  )


  // quick.interactive
  // quick.swing
  // quick.plugins
  // quick.partest
  // quick.scaladoc
  // quick.bin


}
