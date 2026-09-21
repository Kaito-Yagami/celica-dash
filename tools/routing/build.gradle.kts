// The routing graph and the search index for the head unit, from an OSM extract. GraphHopper
// 1.0 on purpose: it is the last version whose routing runs on Android (later ones compile
// weightings with Janino, which the Android runtime cannot load). The app reads the result with
// the same version. `gradlew fatJar` makes setup/bin/dash-import.jar, which RUN ME runs on a
// JRE it downloads - so nobody setting a unit up needs Gradle, or Python packages, for either.
plugins { application; java }
repositories { mavenCentral() }
dependencies {
    implementation("com.graphhopper:graphhopper-reader-osm:1.0")
    implementation("org.slf4j:slf4j-simple:1.7.30")
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
application {
    mainClass.set("Tool")
    applicationDefaultJvmArgs = listOf("-Xmx14g", "-XX:+UseParallelGC")
}
tasks.register<Jar>("fatJar") {
    archiveFileName.set("dash-import.jar")
    destinationDirectory.set(rootProject.file("../../setup/bin"))
    manifest { attributes["Main-Class"] = "Tool" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
