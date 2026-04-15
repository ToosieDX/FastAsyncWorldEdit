tasks.register("build") {
    // Use string-form task paths so Gradle resolves them lazily, after each
    // subproject has had a chance to register its own `build` task. Eager
    // `it.tasks.named("build")` fails under Gradle 9 project isolation because
    // subprojects aren't configured at task-registration time.
    dependsOn(subprojects.map { "${it.path}:build" })
}
