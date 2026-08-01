FROM eclipse-temurin:17-jdk-jammy

ARG ANDROID_COMMAND_LINE_TOOLS=15859902
ARG ANDROID_COMMAND_LINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_PLATFORM=36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK=28.2.13676358
ARG RUST_TOOLCHAIN=1.94.0
ARG CARGO_NDK_VERSION=4.1.2
ARG GRADLE_VERSION=9.4.1
ARG GRADLE_SHA256=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
ARG DEV_UID=1000
ARG DEV_GID=1000

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_NDK_HOME=/opt/android-sdk/ndk/28.2.13676358 \
    CARGO_HOME=/opt/cargo \
    RUSTUP_HOME=/opt/rustup \
    GRADLE_HOME=/opt/gradle/gradle-9.4.1 \
    PATH=/opt/cargo/bin:/opt/gradle/gradle-9.4.1/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        clang \
        cmake \
        curl \
        git \
        liblzma-dev \
        pkg-config \
        shellcheck \
        unzip \
        xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" /opt/gradle \
    && curl -fsSLo /tmp/android-command-line-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS}_latest.zip" \
    && echo "${ANDROID_COMMAND_LINE_TOOLS_SHA256}  /tmp/android-command-line-tools.zip" | sha256sum -c - \
    && unzip -q /tmp/android-command-line-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/android-command-line-tools.zip \
    && { yes || true; } | sdkmanager --licenses >/dev/null \
    && sdkmanager \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "ndk;${ANDROID_NDK}" \
        "platform-tools" \
        "platforms;android-${ANDROID_PLATFORM}"

RUN curl -fsSLo /tmp/gradle.zip \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c - \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm /tmp/gradle.zip

RUN curl --proto '=https' --tlsv1.2 -fsS https://sh.rustup.rs \
        | sh -s -- -y --no-modify-path --default-toolchain "${RUST_TOOLCHAIN}" \
    && rustup component add clippy rustfmt \
    && rustup target add \
        aarch64-linux-android \
        wasm32-unknown-unknown \
        x86_64-linux-android \
    && cargo install cargo-ndk --version "${CARGO_NDK_VERSION}" --locked

RUN groupadd --gid "${DEV_GID}" developer \
    && useradd --uid "${DEV_UID}" --gid "${DEV_GID}" --create-home developer \
    && mkdir -p /workspace/cache/gradle /workspace/target \
    && chown -R developer:developer \
        /opt/cargo \
        /opt/rustup \
        /workspace/cache \
        /workspace/target

WORKDIR /workspace/freenet-android-node

USER developer

CMD ["bash"]
