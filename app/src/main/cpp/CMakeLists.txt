cmake_minimum_required(VERSION 3.22.1)
project(proxydroid_native)

# All legacy native targets (exec/libevent/redsocks) have been removed.
# Native code is now provided by the Rust tun2socks crate, which is
# built by the rust-android-gradle plugin (not by CMake).
#
# A trivial placeholder library is declared so AGP's externalNativeBuild
# CMake task has at least one target to configure successfully.
add_library(proxydroid_native_placeholder STATIC placeholder.c)
