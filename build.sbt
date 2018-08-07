resolvers ++= Seq(
  "Tatami Snapshots" at "https://raw.github.com/cchantep/tatami/master/snapshots")

val silencerVersion = "1.2-SNAPSHOT"

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "reactivemongo" % "0.16.0",
  "com.github.ghik" %% "silencer-lib" % silencerVersion % Provided
)

addCompilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVersion)

scalacOptions in Compile ++= Seq("-Xfatal-warnings", "-deprecation", "-P:silencer:globalFilters=bar\\ in\\ object\\ Foo;lorem")

scalaVersion := "2.12.6"
