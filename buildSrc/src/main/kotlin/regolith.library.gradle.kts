// a module published for someone else to compile against: every public declaration says `public`
// and carries KDoc, and the compiler enforces it.

plugins {
    id("regolith.jvm")
}

kotlin {
    explicitApi()
}
