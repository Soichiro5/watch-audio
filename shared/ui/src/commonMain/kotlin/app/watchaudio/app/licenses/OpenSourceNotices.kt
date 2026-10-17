package app.watchaudio.app.licenses

/** The apps a component ships in (or, for a model, is downloaded by). */
enum class NoticeApp { ANDROID, WEAR_OS, IOS }

enum class NoticeKind {
    /** A model the app downloads (speech recognition, embeddings for semantic search). */
    MODEL,

    /** Code compiled into an app. */
    LIBRARY,
}

/**
 * A license of the components in [OpenSourceNotices]. [text] is the license's full text (shown once
 * on the licenses screen, in the original English) for the short permissive licenses that ask for
 * it to accompany the binary; the others are named by [title] and [url].
 */
enum class License(val title: String, val url: String, val text: String? = null) {
    APACHE_2_0("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0", LicenseTexts.APACHE_2_0),
    MIT("MIT License", "https://spdx.org/licenses/MIT.html", LicenseTexts.MIT),
    BSD_3_CLAUSE("BSD 3-Clause License", "https://spdx.org/licenses/BSD-3-Clause.html", LicenseTexts.BSD_3_CLAUSE),
    PUBLIC_DOMAIN("Public domain", "https://www.sqlite.org/copyright.html"),
    FUNASR_MODEL_1_1("FunASR Model Open Source License Agreement v1.1", "https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE"),
    ANDROID_SDK("Android Software Development Kit License", "https://developer.android.com/studio/terms"),
    MLKIT_TERMS("ML Kit Terms of Service", "https://developers.google.com/ml-kit/terms"),
    CC0_1_0("CC0 1.0 Universal (public domain dedication)", "https://creativecommons.org/publicdomain/zero/1.0/"),
    UNICODE_3("Unicode License v3", "https://github.com/unicode-org/icu/blob/main/LICENSE"),
    HARFBUZZ_OLD_MIT("Old MIT License (HarfBuzz)", "https://github.com/harfbuzz/harfbuzz/blob/main/COPYING"),
    LIBPNG_2("PNG Reference Library License version 2", "https://github.com/pnggroup/libpng/blob/libpng16/LICENSE"),
    ZLIB("zlib License", "https://github.com/madler/zlib/blob/master/LICENSE"),
    IJG_BSD_3("IJG License and BSD 3-Clause License", "https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/LICENSE.md"),
}

/**
 * One third-party component with its attribution: [name], [author], [license] and the project's
 * [url]. [copyright] is the notice the license asks to keep (MIT / BSD), [includedIn] names the
 * component whose binaries carry this one (e.g. ONNX Runtime inside sherpa-onnx), and [downloads]
 * are the URL prefixes a model's files are fetched from (the app's model manifest).
 */
data class OpenSourceNotice(
    val name: String,
    val author: String,
    val license: License,
    val url: String,
    val kind: NoticeKind,
    val apps: Set<NoticeApp>,
    val copyright: String? = null,
    val includedIn: String? = null,
    val downloads: List<String> = emptyList(),
)

/**
 * The third-party components the apps ship or download, shown on the licenses screen (#167).
 *
 * Collected from the release runtime classpaths of `apps/android` and `apps/wear`, the iOS
 * framework's klibs (`shared/ui`), the native libraries the app ships (sherpa-onnx's JNI library as
 * CI builds it for distributed APKs, `tools/sherpa-onnx-android/build-jni.sh`; Skiko), the iOS
 * project's Swift packages (`apps/ios/project.yml`), and the models in the Android model manifest
 * and `RuriV3.FILES`. A new dependency or model needs an entry here (the model lists are checked by
 * unit tests).
 */
object OpenSourceNotices {
    private val PHONES = setOf(NoticeApp.ANDROID, NoticeApp.IOS)
    private val ANDROID_APPS = setOf(NoticeApp.ANDROID, NoticeApp.WEAR_OS)
    private val ALL_APPS = NoticeApp.entries.toSet()
    private val ANDROID = setOf(NoticeApp.ANDROID)
    private val IOS = setOf(NoticeApp.IOS)

    private const val SHERPA_ONNX = "sherpa-onnx"
    private const val SKIKO = "Skiko"

    val REAZON_SPEECH = OpenSourceNotice(
        name = "ReazonSpeech k2 v2 (reazonspeech-k2-v2)",
        author = "reazon-research (ReazonSpeech)",
        license = License.APACHE_2_0,
        url = "https://huggingface.co/reazon-research/reazonspeech-k2-v2",
        kind = NoticeKind.MODEL,
        apps = ANDROID,
        downloads = listOf("https://huggingface.co/csukuangfj/reazonspeech-k2-v2/"),
    )

    val SILERO_VAD = OpenSourceNotice(
        name = "Silero VAD",
        author = "Silero Team",
        license = License.MIT,
        url = "https://github.com/snakers4/silero-vad",
        kind = NoticeKind.MODEL,
        apps = ANDROID,
        copyright = "Copyright (c) 2020-present Silero Team",
        downloads = listOf("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"),
    )

    /** The English model (#81): only downloaded while 「英語も文字起こしする」 is on. */
    val SENSE_VOICE = OpenSourceNotice(
        name = "SenseVoice Small",
        author = "FunASR / Alibaba Group",
        license = License.FUNASR_MODEL_1_1,
        url = "https://huggingface.co/FunAudioLLM/SenseVoiceSmall",
        kind = NoticeKind.MODEL,
        apps = ANDROID,
        copyright = "Copyright (C) 2023-2028 Alibaba Group. All rights reserved.",
        downloads = listOf("https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/"),
    )

    /** The embedding model of semantic search (#24, iOS #178): downloaded automatically, Wi-Fi only. */
    val RURI_V3 = OpenSourceNotice(
        name = "Ruri v3 30m (ruri-v3-30m)",
        author = "Hayato Tsukagoshi, Ryohei Sasano (Nagoya University, cl-nagoya)",
        license = License.APACHE_2_0,
        url = "https://huggingface.co/cl-nagoya/ruri-v3-30m",
        kind = NoticeKind.MODEL,
        apps = PHONES,
        downloads = listOf("https://huggingface.co/onnx-community/ruri-v3-30m-ONNX/", "https://huggingface.co/cl-nagoya/ruri-v3-30m/"),
    )

    private val models = listOf(REAZON_SPEECH, SILERO_VAD, SENSE_VOICE, RURI_V3)

    private val libraries = listOf(
        // Kotlin and Jetpack / Compose
        library("Kotlin Standard Library", "JetBrains", License.APACHE_2_0, "https://github.com/JetBrains/kotlin", ALL_APPS),
        library("kotlinx.coroutines", "JetBrains", License.APACHE_2_0, "https://github.com/Kotlin/kotlinx.coroutines", ALL_APPS),
        library("kotlinx.serialization", "JetBrains", License.APACHE_2_0, "https://github.com/Kotlin/kotlinx.serialization", ALL_APPS),
        library("kotlinx-io", "JetBrains", License.APACHE_2_0, "https://github.com/Kotlin/kotlinx-io", ALL_APPS),
        library("kotlinx-datetime", "JetBrains", License.APACHE_2_0, "https://github.com/Kotlin/kotlinx-datetime", PHONES),
        library("atomicfu", "JetBrains", License.APACHE_2_0, "https://github.com/Kotlin/kotlinx-atomicfu", IOS),
        library("JetBrains Java Annotations", "JetBrains", License.APACHE_2_0, "https://github.com/JetBrains/java-annotations", ANDROID_APPS),
        library("Compose Multiplatform", "JetBrains", License.APACHE_2_0, "https://github.com/JetBrains/compose-multiplatform", PHONES),
        library("AndroidX (Jetpack)", "The Android Open Source Project", License.APACHE_2_0, "https://developer.android.com/jetpack/androidx", ALL_APPS),
        library(
            "SQLite", "D. Richard Hipp and the SQLite developers", License.PUBLIC_DOMAIN, "https://www.sqlite.org/",
            PHONES, includedIn = "AndroidX SQLite (sqlite-bundled)",
        ),
        library("Guava: ListenableFuture", "Google", License.APACHE_2_0, "https://github.com/google/guava", ANDROID_APPS),
        library("JSpecify", "The JSpecify Authors", License.APACHE_2_0, "https://github.com/jspecify/jspecify", ANDROID_APPS),
        library(
            "Google Play services (Wearable, Base, Basement, Tasks)", "Google", License.ANDROID_SDK,
            "https://developers.google.com/android/guides/overview", ANDROID_APPS,
        ),
        library(
            "Protocol Buffers", "Google", License.BSD_3_CLAUSE, "https://github.com/protocolbuffers/protobuf",
            setOf(NoticeApp.WEAR_OS), copyright = "Copyright 2008 Google Inc. All rights reserved.",
            includedIn = "AndroidX Wear ProtoLayout",
        ),
        // On-device speech recognition (Android) and what its native libraries contain. Distributed
        // APKs pack libsherpa-onnx-jni.so built without text-to-speech and speaker diarization (#173,
        // checked by CI), so eSpeak NG (GPL-3.0), piper-phonemize and hclust-cpp are not shipped.
        // Local development builds keep the AAR's library (with them); they are never distributed.
        library(SHERPA_ONNX, "k2-fsa (Next-gen Kaldi), Xiaomi Corporation", License.APACHE_2_0, "https://github.com/k2-fsa/sherpa-onnx", ANDROID),
        library(
            "ONNX Runtime", "Microsoft", License.MIT, "https://github.com/microsoft/onnxruntime", ANDROID,
            copyright = "Copyright (c) Microsoft Corporation", includedIn = SHERPA_ONNX,
        ),
        library(
            "JNA (Java Native Access)", "JNA contributors", License.APACHE_2_0, "https://github.com/java-native-access/jna", ANDROID,
        ),
        // Reads the agent access pairing QR code from a photo (#184).
        library("ZXing", "ZXing authors", License.APACHE_2_0, "https://github.com/zxing/zxing", ANDROID),
        // Hour summaries with Gemini Nano (#205); ML Kit sends content-free usage logs through Data Transport.
        library("ML Kit GenAI Prompt API and ML Kit common", "Google", License.MLKIT_TERMS, "https://developers.google.com/ml-kit/genai", ANDROID),
        library(
            "Google Data Transport, Firebase Encoders and Components", "Google", License.APACHE_2_0,
            "https://github.com/firebase/firebase-android-sdk", ANDROID,
        ),
        // What ML Kit GenAI's Kotlin API brings along (kotlinx-coroutines-guava / -reactive).
        library("Guava", "Google", License.APACHE_2_0, "https://github.com/google/guava", ANDROID),
        library("Error Prone annotations", "Google", License.APACHE_2_0, "https://github.com/google/error-prone", ANDROID),
        library("J2ObjC annotations", "Google", License.APACHE_2_0, "https://github.com/google/j2objc", ANDROID),
        library("JSR 305 annotations", "FindBugs", License.APACHE_2_0, "https://github.com/findbugsproject/findbugs", ANDROID),
        library("javax.inject", "The JSR-330 Expert Group", License.APACHE_2_0, "https://github.com/javax-inject/javax-inject", ANDROID),
        library(
            "Checker Framework qualifiers", "Checker Framework developers", License.MIT, "https://github.com/typetools/checker-framework",
            ANDROID, copyright = "Copyright 2004-present by the Checker Framework developers",
        ),
        library("Reactive Streams", "Reactive Streams SIG", License.CC0_1_0, "https://github.com/reactive-streams/reactive-streams-jvm", ANDROID),
        library("kaldi-native-fbank", "k2-fsa", License.APACHE_2_0, "https://github.com/csukuangfj/kaldi-native-fbank", ANDROID, includedIn = SHERPA_ONNX),
        library("kaldi-decoder", "k2-fsa", License.APACHE_2_0, "https://github.com/k2-fsa/kaldi-decoder", ANDROID, includedIn = SHERPA_ONNX),
        library("kaldifst", "k2-fsa", License.APACHE_2_0, "https://github.com/csukuangfj/kaldifst", ANDROID, includedIn = SHERPA_ONNX),
        library("OpenFst", "The OpenFst Authors", License.APACHE_2_0, "https://github.com/csukuangfj/openfst", ANDROID, includedIn = SHERPA_ONNX),
        library("simple-sentencepiece", "pkufool", License.APACHE_2_0, "https://github.com/pkufool/simple-sentencepiece", ANDROID, includedIn = SHERPA_ONNX),
        library(
            "JSON for Modern C++", "Niels Lohmann", License.MIT, "https://github.com/nlohmann/json", ANDROID,
            copyright = "Copyright (c) 2013-2025 Niels Lohmann", includedIn = SHERPA_ONNX,
        ),
        // The embedding model of semantic search on the iPhone (#178): the official Swift package, its
        // Objective-C API over the prebuilt runtime, linked into the app.
        library(
            "ONNX Runtime for iOS (onnxruntime-swift-package-manager)", "Microsoft", License.MIT,
            "https://github.com/microsoft/onnxruntime-swift-package-manager", IOS, copyright = "Copyright (c) Microsoft Corporation",
        ),
        // Compose Multiplatform's renderer on iOS and what it links
        library(SKIKO, "JetBrains", License.APACHE_2_0, "https://github.com/JetBrains/skiko", IOS),
        library(
            "Skia", "Google", License.BSD_3_CLAUSE, "https://skia.org/", IOS,
            copyright = "Copyright (c) 2011 Google Inc. All rights reserved.", includedIn = SKIKO,
        ),
        library("ICU", "Unicode, Inc.", License.UNICODE_3, "https://github.com/unicode-org/icu", IOS, copyright = "Copyright © 1991-2026 Unicode, Inc.", includedIn = SKIKO),
        library("HarfBuzz", "HarfBuzz contributors", License.HARFBUZZ_OLD_MIT, "https://github.com/harfbuzz/harfbuzz", IOS, includedIn = SKIKO),
        library("libpng", "The PNG Reference Library Authors", License.LIBPNG_2, "https://github.com/pnggroup/libpng", IOS, includedIn = SKIKO),
        library("zlib", "Jean-loup Gailly, Mark Adler", License.ZLIB, "https://github.com/madler/zlib", IOS, includedIn = SKIKO),
        library(
            "Expat", "Thai Open Source Software Center Ltd, Clark Cooper, Expat maintainers", License.MIT,
            "https://github.com/libexpat/libexpat", IOS,
            copyright = "Copyright (c) 1998-2000 Thai Open Source Software Center Ltd and Clark Cooper. Copyright (c) 2001-2025 Expat maintainers",
            includedIn = SKIKO,
        ),
        library(
            "libjpeg-turbo", "libjpeg-turbo authors, Independent JPEG Group", License.IJG_BSD_3,
            "https://github.com/libjpeg-turbo/libjpeg-turbo", IOS, includedIn = SKIKO,
        ),
        library(
            "libwebp", "Google", License.BSD_3_CLAUSE, "https://github.com/webmproject/libwebp", IOS,
            copyright = "Copyright (c) 2010, Google Inc. All rights reserved.", includedIn = SKIKO,
        ),
    )

    /** Every component of every app: models first, then libraries. */
    val all: List<OpenSourceNotice> = models + libraries

    /** The components of [apps] (a phone app and its watch app), in [all]'s order. */
    fun forApps(apps: Set<NoticeApp>): List<OpenSourceNotice> = all.filter { notice -> notice.apps.any { it in apps } }

    /** The licenses [notices] use, each once, in the order of [License]. */
    fun licensesOf(notices: List<OpenSourceNotice>): List<License> = notices.map { it.license }.distinct().sortedBy { it.ordinal }

    private fun library(
        name: String,
        author: String,
        license: License,
        url: String,
        apps: Set<NoticeApp>,
        copyright: String? = null,
        includedIn: String? = null,
    ) = OpenSourceNotice(name, author, license, url, NoticeKind.LIBRARY, apps, copyright, includedIn)
}
