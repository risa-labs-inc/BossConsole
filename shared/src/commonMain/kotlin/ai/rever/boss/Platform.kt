package ai.rever.boss

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform