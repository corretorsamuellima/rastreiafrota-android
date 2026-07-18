import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Assinatura release fora do repositório (keystore.properties)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.rastreiafrota.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rastreiafrota.app.audio.plus"
        minSdk = 26            // Android 8.0
        targetSdk = 34
        versionCode = 7
        versionName = "1.3.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // URL de desenvolvimento configurável APENAS em debug (tela de ativação permite alterar)
            buildConfigField("String", "BASE_URL", "\"http://192.168.1.103/painel-web/\"")
            buildConfigField("boolean", "ALLOW_SERVER_CHANGE", "true")
        }
        release {
            // AJUSTE AQUI a URL de produção (HTTPS obrigatório) antes de gerar o release
            buildConfigField("String", "BASE_URL", "\"https://rastreio.seudominio.com.br/\"")
            // A troca exige nova ativação, evitando reutilizar credenciais em outro servidor.
            buildConfigField("boolean", "ALLOW_SERVER_CHANGE", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Localização
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Room (fila offline)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Rede
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager (sincronização com retentativa exponencial)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore (preferências)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Segurança (tokens criptografados via Android Keystore)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
