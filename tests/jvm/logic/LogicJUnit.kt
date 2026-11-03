package airscope.tests.logic

import org.junit.Test

/** Runs the plain-Kotlin logic suite under Gradle (`./gradlew testReleaseUnitTest`); CI fails the build on any FAIL line. */
class LogicJUnit {
    @Test fun logicSuite() = main()
}
