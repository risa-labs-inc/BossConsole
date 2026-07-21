package ai.rever.boss.project.templates

/**
 * Static file content definitions for all project templates.
 * Uses placeholder tokens that are replaced at project creation time:
 * - {PROJECT_NAME} - The project name entered by the user
 * - {PACKAGE_NAME} - Package name derived from project name (lowercase, no spaces/special chars)
 */
object TemplateDefinitions {

    // ========== Empty Project ==========

    val emptyFiles: List<TemplateFile> = listOf(
        TemplateFile(
            relativePath = ".gitignore",
            content = """
                # IDE
                .idea/
                *.iml
                .vscode/

                # OS
                .DS_Store
                Thumbs.db

                # Build outputs
                build/
                dist/
                out/
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "README.md",
            content = """
                # {PROJECT_NAME}

                A new project created with BOSS Console.

                ## Getting Started

                Add your project description and instructions here.
            """.trimIndent()
        )
    )

    // ========== Kotlin/JVM Project ==========

    val kotlinJvmFiles: List<TemplateFile> = listOf(
        TemplateFile(
            relativePath = "build.gradle.kts",
            content = """
                plugins {
                    kotlin("jvm") version "2.0.21"
                    application
                }

                group = "{PACKAGE_NAME}"
                version = "1.0-SNAPSHOT"

                repositories {
                    mavenCentral()
                }

                dependencies {
                    testImplementation(kotlin("test"))
                }

                tasks.test {
                    useJUnitPlatform()
                }

                kotlin {
                    jvmToolchain(21)
                }

                application {
                    mainClass.set("{PACKAGE_NAME}.MainKt")
                }
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "{PROJECT_NAME}"
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "gradle.properties",
            content = """
                kotlin.code.style=official
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "src/main/kotlin/Main.kt",
            content = """
                package {PACKAGE_NAME}

                fun main() {
                    println("Hello, {PROJECT_NAME}!")
                }
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "src/test/kotlin/.gitkeep",
            content = ""
        ),
        TemplateFile(
            relativePath = ".gitignore",
            content = """
                # Gradle
                .gradle/
                build/
                !gradle/wrapper/gradle-wrapper.jar

                # IDE
                .idea/
                *.iml
                .vscode/

                # OS
                .DS_Store
                Thumbs.db

                # Kotlin
                *.class
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "README.md",
            content = """
                # {PROJECT_NAME}

                A Kotlin/JVM project created with BOSS Console.

                ## Building

                ```bash
                ./gradlew build
                ```

                ## Running

                ```bash
                ./gradlew run
                ```

                ## Testing

                ```bash
                ./gradlew test
                ```
            """.trimIndent()
        )
    )

    // ========== Node.js Project ==========

    val nodeJsFiles: List<TemplateFile> = listOf(
        TemplateFile(
            relativePath = "package.json",
            content = """
                {
                  "name": "{PACKAGE_NAME}",
                  "version": "1.0.0",
                  "description": "A Node.js project created with BOSS Console",
                  "main": "index.js",
                  "scripts": {
                    "start": "node index.js",
                    "test": "echo \"Error: no test specified\" && exit 1"
                  },
                  "keywords": [],
                  "author": "",
                  "license": "ISC"
                }
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "index.js",
            content = """
                // {PROJECT_NAME}
                // A Node.js project created with BOSS Console

                console.log('Hello from {PROJECT_NAME}!');
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = ".gitignore",
            content = """
                # Dependencies
                node_modules/

                # Build outputs
                dist/
                build/

                # Environment
                .env
                .env.local
                .env.*.local

                # IDE
                .idea/
                .vscode/
                *.iml

                # OS
                .DS_Store
                Thumbs.db

                # Logs
                *.log
                npm-debug.log*
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "README.md",
            content = """
                # {PROJECT_NAME}

                A Node.js project created with BOSS Console.

                ## Getting Started

                ```bash
                npm install
                npm start
                ```
            """.trimIndent()
        )
    )

    // ========== Go Project ==========

    val goFiles: List<TemplateFile> = listOf(
        TemplateFile(
            relativePath = "go.mod",
            content = """
                module {PACKAGE_NAME}

                go 1.22
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "main.go",
            content = """
                package main

                import "fmt"

                func main() {
                	fmt.Println("Hello from {PROJECT_NAME}!")
                }
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = ".gitignore",
            content = """
                # Binaries
                *.exe
                *.exe~
                *.dll
                *.so
                *.dylib

                # Test binary
                *.test

                # Output
                /bin/
                /build/

                # Go workspace
                go.work

                # IDE
                .idea/
                .vscode/
                *.iml

                # OS
                .DS_Store
                Thumbs.db
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "README.md",
            content = """
                # {PROJECT_NAME}

                A Go project created with BOSS Console.

                ## Building

                ```bash
                go build
                ```

                ## Running

                ```bash
                go run .
                ```

                ## Testing

                ```bash
                go test ./...
                ```
            """.trimIndent()
        )
    )

    // ========== Rust Project ==========

    val rustFiles: List<TemplateFile> = listOf(
        TemplateFile(
            relativePath = "Cargo.toml",
            content = """
                [package]
                name = "{PACKAGE_NAME}"
                version = "0.1.0"
                edition = "2021"

                [dependencies]
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "src/main.rs",
            content = """
                fn main() {
                    println!("Hello from {PROJECT_NAME}!");
                }
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = ".gitignore",
            content = """
                # Cargo
                /target/
                Cargo.lock

                # IDE
                .idea/
                .vscode/
                *.iml

                # OS
                .DS_Store
                Thumbs.db
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "README.md",
            content = """
                # {PROJECT_NAME}

                A Rust project created with BOSS Console.

                ## Building

                ```bash
                cargo build
                ```

                ## Running

                ```bash
                cargo run
                ```

                ## Testing

                ```bash
                cargo test
                ```
            """.trimIndent()
        )
    )

    // ========== Python Project ==========

    val pythonFiles: List<TemplateFile> = listOf(
        TemplateFile(
            relativePath = "pyproject.toml",
            content = """
                [build-system]
                requires = ["setuptools>=61.0"]
                build-backend = "setuptools.build_meta"

                [project]
                name = "{PACKAGE_NAME}"
                version = "0.1.0"
                description = "A Python project created with BOSS Console"
                readme = "README.md"
                requires-python = ">=3.10"
                dependencies = []

                [project.optional-dependencies]
                dev = [
                    "pytest>=7.0",
                ]
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "main.py",
            content = """
                #!/usr/bin/env python3
                \"\"\"
                {PROJECT_NAME}
                A Python project created with BOSS Console.
                \"\"\"


                def main():
                    print("Hello from {PROJECT_NAME}!")


                if __name__ == "__main__":
                    main()
            """.trimIndent(),
            isExecutable = true
        ),
        TemplateFile(
            relativePath = "requirements.txt",
            content = """
                # Add your dependencies here
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = ".gitignore",
            content = """
                # Python
                __pycache__/
                *.py[cod]
                *${'$'}py.class
                *.so

                # Virtual environments
                .venv/
                venv/
                ENV/
                env/

                # Distribution / packaging
                dist/
                build/
                *.egg-info/

                # IDE
                .idea/
                .vscode/
                *.iml

                # OS
                .DS_Store
                Thumbs.db

                # Testing
                .pytest_cache/
                .coverage
                htmlcov/
            """.trimIndent()
        ),
        TemplateFile(
            relativePath = "README.md",
            content = """
                # {PROJECT_NAME}

                A Python project created with BOSS Console.

                ## Setup

                ```bash
                python -m venv .venv
                source .venv/bin/activate  # On Windows: .venv\Scripts\activate
                pip install -e ".[dev]"
                ```

                ## Running

                ```bash
                python main.py
                ```

                ## Testing

                ```bash
                pytest
                ```
            """.trimIndent()
        )
    )
}
