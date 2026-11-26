//! Link-time settings for the Android builds.

fn main() {
    // Android 15+ can run with 16 KB memory pages, and such devices refuse to
    // load a shared library whose LOAD segments are only 4 KB aligned. NDK r25
    // (pinned by the Gradle build) still links with a 4 KB maximum page size
    // by default, so ask for 16 KB explicitly. A 16 KB-aligned library loads
    // fine on 4 KB devices too.
    //
    // https://developer.android.com/guide/practices/page-sizes
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
    }
}
