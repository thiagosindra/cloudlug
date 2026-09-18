// Settings every Android module shares (spec §3): the SDK levels, Java 17
// bytecode to match the JVM modules, and the same warnings-as-errors rule.
import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<BaseExtension>("android") {
    compileSdkVersion(libs.findVersion("compileSdk").get().requiredVersion.toInt())

    defaultConfig {
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}
