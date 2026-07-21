package ai.rever.boss.plugin.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.SimpleIcons
import compose.icons.simpleicons.*

/**
 * Centralized language/technology icons using official brand icons from Simple Icons.
 *
 * This provides a single source of truth for language icons and their official brand colors
 * across the codebase (Project Templates, File Tree, Run Configurations, etc.)
 *
 * Categories:
 * - Programming Languages (38)
 * - Web Frameworks (37)
 * - CSS Frameworks (12)
 * - Databases (20)
 * - DevOps & Cloud (25)
 * - Build Tools & Bundlers (23)
 * - Testing Tools (10)
 * - Linters & Formatters (5)
 * - API & Data (11)
 * - AI/ML (5)
 * - Mobile (6)
 * - Shell (2)
 * - Data Formats (4)
 *
 * Total: ~180 icons
 */
object LanguageIcons {

    // ═══════════════════════════════════════════════════════════════════════════
    // PROGRAMMING LANGUAGES (38)
    // ═══════════════════════════════════════════════════════════════════════════

    val kotlin: ImageVector = SimpleIcons.Kotlin
    val java: ImageVector = SimpleIcons.Java
    val python: ImageVector = SimpleIcons.Python
    val javascript: ImageVector = SimpleIcons.Javascript
    val typescript: ImageVector = SimpleIcons.Typescript
    val go: ImageVector = SimpleIcons.Go
    val rust: ImageVector = SimpleIcons.Rust
    val swift: ImageVector = SimpleIcons.Swift
    val cpp: ImageVector = SimpleIcons.Cplusplus
    val c: ImageVector = SimpleIcons.C
    val csharp: ImageVector = SimpleIcons.Csharp
    val ruby: ImageVector = SimpleIcons.Ruby
    val php: ImageVector = SimpleIcons.Php
    val scala: ImageVector = SimpleIcons.Scala
    val haskell: ImageVector = SimpleIcons.Haskell
    val lua: ImageVector = SimpleIcons.Lua
    val perl: ImageVector = SimpleIcons.Perl
    val r: ImageVector = SimpleIcons.R
    val dart: ImageVector = SimpleIcons.Dart
    val elixir: ImageVector = SimpleIcons.Elixir
    val clojure: ImageVector = SimpleIcons.Clojure
    val julia: ImageVector = SimpleIcons.Julia
    val ocaml: ImageVector = SimpleIcons.Ocaml
    val zig: ImageVector = Icons.Outlined.Code  // Zig not in Simple Icons 1.1.1
    val objectivec: ImageVector = Icons.Outlined.Code  // Objective-C not in Simple Icons 1.1.1
    val fsharp: ImageVector = Icons.Outlined.Code  // F# not in Simple Icons 1.1.1
    val erlang: ImageVector = SimpleIcons.Erlang
    val nim: ImageVector = SimpleIcons.Nim
    val crystal: ImageVector = SimpleIcons.Crystal
    val fortran: ImageVector = SimpleIcons.Fortran
    val cobol: ImageVector = Icons.Outlined.Code  // COBOL not in Simple Icons
    val assembly: ImageVector = SimpleIcons.Assemblyscript
    val solidity: ImageVector = SimpleIcons.Solidity
    val vlang: ImageVector = SimpleIcons.V
    val dlang: ImageVector = Icons.Outlined.Code  // D not in Simple Icons 1.1.1
    val groovy: ImageVector = SimpleIcons.Apachegroovy
    val rescript: ImageVector = Icons.Outlined.Code  // ReScript not in Simple Icons
    val racket: ImageVector = Icons.Outlined.Code  // Racket not in Simple Icons

    // ═══════════════════════════════════════════════════════════════════════════
    // WEB FRAMEWORKS & RUNTIMES (37)
    // ═══════════════════════════════════════════════════════════════════════════

    val nodejs: ImageVector = SimpleIcons.NodeDotJs
    val deno: ImageVector = SimpleIcons.Deno
    val bun: ImageVector = Icons.Outlined.Terminal  // Bun not in Simple Icons 1.1.1
    val spring: ImageVector = SimpleIcons.Spring
    val springboot: ImageVector = SimpleIcons.Spring  // Use Spring icon
    val django: ImageVector = SimpleIcons.Django
    val flask: ImageVector = SimpleIcons.Flask
    val fastapi: ImageVector = SimpleIcons.Fastapi
    val rails: ImageVector = SimpleIcons.Rubyonrails
    val laravel: ImageVector = SimpleIcons.Laravel
    val symfony: ImageVector = SimpleIcons.Symfony
    val react: ImageVector = SimpleIcons.React
    val vue: ImageVector = SimpleIcons.VueDotJs
    val angular: ImageVector = SimpleIcons.Angular
    val svelte: ImageVector = SimpleIcons.Svelte
    val nextjs: ImageVector = SimpleIcons.NextDotJs
    val nuxt: ImageVector = SimpleIcons.NuxtDotJs
    val astro: ImageVector = Icons.Outlined.Code  // Astro not in Simple Icons 1.1.1
    val remix: ImageVector = Icons.Outlined.Code  // Remix not in Simple Icons 1.1.1
    val flutter: ImageVector = SimpleIcons.Flutter
    val express: ImageVector = SimpleIcons.Express
    val nestjs: ImageVector = SimpleIcons.Nestjs
    val ktor: ImageVector = Icons.Outlined.Code  // Ktor not in Simple Icons 1.1.1
    val gin: ImageVector = SimpleIcons.Go  // Gin uses Go icon
    val phoenix: ImageVector = SimpleIcons.Elixir  // Phoenix uses Elixir icon
    val blazor: ImageVector = SimpleIcons.Blazor
    val solid: ImageVector = Icons.Outlined.Code  // Solid not in Simple Icons 1.1.1
    val lit: ImageVector = Icons.Outlined.Code  // Lit not in Simple Icons 1.1.1
    val qwik: ImageVector = Icons.Outlined.Code  // Qwik not in Simple Icons 1.1.1
    val htmx: ImageVector = Icons.Outlined.Code  // htmx not in Simple Icons 1.1.1
    val alpinejs: ImageVector = Icons.Outlined.Code  // Alpine.js not in Simple Icons 1.1.1
    val emberjs: ImageVector = SimpleIcons.EmberDotJs
    val backbonejs: ImageVector = Icons.Outlined.Code  // Backbone.js not in Simple Icons 1.1.1
    val preact: ImageVector = SimpleIcons.React  // Preact uses React icon
    val electron: ImageVector = SimpleIcons.Electron
    val tauri: ImageVector = Icons.Outlined.Code  // Tauri not in Simple Icons 1.1.1
    val fastify: ImageVector = SimpleIcons.Fastify

    // ═══════════════════════════════════════════════════════════════════════════
    // CSS FRAMEWORKS & PREPROCESSORS (12)
    // ═══════════════════════════════════════════════════════════════════════════

    val tailwindcss: ImageVector = SimpleIcons.Tailwindcss
    val bootstrap: ImageVector = SimpleIcons.Bootstrap
    val sass: ImageVector = SimpleIcons.Sass
    val less: ImageVector = SimpleIcons.Less
    val stylus: ImageVector = SimpleIcons.Stylus
    val postcss: ImageVector = SimpleIcons.Postcss
    val styledcomponents: ImageVector = Icons.Outlined.Code  // styled-components not in Simple Icons 1.1.1
    val chakraui: ImageVector = SimpleIcons.Chakraui
    val mui: ImageVector = SimpleIcons.Materialdesign  // MUI uses Material Design icon
    val bulma: ImageVector = SimpleIcons.Bulma
    val foundation: ImageVector = Icons.Outlined.Code  // Foundation not in Simple Icons 1.1.1
    val antdesign: ImageVector = SimpleIcons.Antdesign

    // ═══════════════════════════════════════════════════════════════════════════
    // DATABASES (20)
    // ═══════════════════════════════════════════════════════════════════════════

    val postgresql: ImageVector = SimpleIcons.Postgresql
    val mysql: ImageVector = SimpleIcons.Mysql
    val mongodb: ImageVector = SimpleIcons.Mongodb
    val redis: ImageVector = SimpleIcons.Redis
    val sqlite: ImageVector = SimpleIcons.Sqlite
    val mariadb: ImageVector = SimpleIcons.Mariadb
    val oracle: ImageVector = SimpleIcons.Oracle
    val sqlserver: ImageVector = SimpleIcons.Microsoftsqlserver
    val cassandra: ImageVector = SimpleIcons.Apachecassandra
    val couchdb: ImageVector = Icons.Outlined.Code  // CouchDB not in Simple Icons 1.1.1
    val dynamodb: ImageVector = SimpleIcons.Amazondynamodb
    val elasticsearch: ImageVector = SimpleIcons.Elasticsearch
    val neo4j: ImageVector = Icons.Outlined.Code  // Neo4j not in Simple Icons 1.1.1
    val influxdb: ImageVector = SimpleIcons.Influxdb
    val firebase: ImageVector = SimpleIcons.Firebase
    val supabase: ImageVector = SimpleIcons.Supabase
    val planetscale: ImageVector = Icons.Outlined.Code  // PlanetScale not in Simple Icons 1.1.1
    val prisma: ImageVector = SimpleIcons.Prisma
    val cockroachdb: ImageVector = SimpleIcons.Cockroachlabs
    val duckdb: ImageVector = Icons.Outlined.Code  // DuckDB not in Simple Icons 1.1.1

    // ═══════════════════════════════════════════════════════════════════════════
    // DEVOPS & CLOUD (25)
    // ═══════════════════════════════════════════════════════════════════════════

    val docker: ImageVector = SimpleIcons.Docker
    val kubernetes: ImageVector = SimpleIcons.Kubernetes
    val git: ImageVector = SimpleIcons.Git
    val github: ImageVector = SimpleIcons.Github
    val gitlab: ImageVector = SimpleIcons.Gitlab
    val bitbucket: ImageVector = SimpleIcons.Bitbucket
    val aws: ImageVector = SimpleIcons.Amazonaws
    val googlecloud: ImageVector = SimpleIcons.Googlecloud
    val azure: ImageVector = SimpleIcons.Microsoftazure
    val terraform: ImageVector = SimpleIcons.Terraform
    val ansible: ImageVector = SimpleIcons.Ansible
    val pulumi: ImageVector = Icons.Outlined.Code  // Pulumi not in Simple Icons 1.1.1
    val jenkins: ImageVector = SimpleIcons.Jenkins
    val githubactions: ImageVector = SimpleIcons.Githubactions
    val circleci: ImageVector = SimpleIcons.Circleci
    val travisci: ImageVector = SimpleIcons.Travisci
    val vercel: ImageVector = SimpleIcons.Vercel
    val netlify: ImageVector = SimpleIcons.Netlify
    val railway: ImageVector = Icons.Outlined.Code  // Railway not in Simple Icons 1.1.1
    val render: ImageVector = Icons.Outlined.Code  // Render not in Simple Icons 1.1.1
    val heroku: ImageVector = SimpleIcons.Heroku
    val digitalocean: ImageVector = SimpleIcons.Digitalocean
    val cloudflare: ImageVector = SimpleIcons.Cloudflare
    val nginx: ImageVector = SimpleIcons.Nginx
    val apache: ImageVector = SimpleIcons.Apache

    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD TOOLS & BUNDLERS (23)
    // ═══════════════════════════════════════════════════════════════════════════

    val gradle: ImageVector = SimpleIcons.Gradle
    val maven: ImageVector = SimpleIcons.Apachemaven
    val npm: ImageVector = SimpleIcons.Npm
    val yarn: ImageVector = SimpleIcons.Yarn
    val pnpm: ImageVector = SimpleIcons.Npm  // PNPM uses NPM icon
    val cargo: ImageVector = SimpleIcons.Rust  // Cargo uses Rust icon
    val pip: ImageVector = SimpleIcons.Pypi
    val vite: ImageVector = Icons.Outlined.Code  // Vite not in Simple Icons 1.1.1
    val webpack: ImageVector = SimpleIcons.Webpack
    val rollup: ImageVector = SimpleIcons.RollupDotJs
    val parcel: ImageVector = Icons.Outlined.Code  // Parcel not in Simple Icons
    val esbuild: ImageVector = Icons.Outlined.Code  // esbuild not in Simple Icons 1.1.1
    val swc: ImageVector = Icons.Outlined.Code  // SWC not in Simple Icons 1.1.1
    val turborepo: ImageVector = Icons.Outlined.Code  // Turborepo not in Simple Icons 1.1.1
    val nx: ImageVector = SimpleIcons.Nx
    val lerna: ImageVector = Icons.Outlined.Code  // Lerna not in Simple Icons 1.1.1
    val bazel: ImageVector = Icons.Outlined.Code  // Bazel not in Simple Icons
    val cmake: ImageVector = SimpleIcons.Cmake
    val gnumake: ImageVector = Icons.Outlined.Terminal  // GNU Make not in Simple Icons 1.1.1
    val meson: ImageVector = Icons.Outlined.Code  // Meson not in Simple Icons 1.1.1
    val gulp: ImageVector = SimpleIcons.Gulp
    val grunt: ImageVector = SimpleIcons.Grunt
    val sbt: ImageVector = SimpleIcons.Scala  // SBT uses Scala icon

    // ═══════════════════════════════════════════════════════════════════════════
    // TESTING TOOLS (10)
    // ═══════════════════════════════════════════════════════════════════════════

    val jest: ImageVector = SimpleIcons.Jest
    val vitest: ImageVector = Icons.Outlined.Code  // Vitest not in Simple Icons 1.1.1
    val cypress: ImageVector = SimpleIcons.Cypress
    val playwright: ImageVector = Icons.Outlined.Code  // Playwright not in Simple Icons 1.1.1
    val selenium: ImageVector = SimpleIcons.Selenium
    val pytest: ImageVector = SimpleIcons.Python  // Pytest uses Python icon
    val mocha: ImageVector = SimpleIcons.Mocha
    val jasmine: ImageVector = SimpleIcons.Jasmine
    val testinglibrary: ImageVector = Icons.Outlined.Code  // Testing Library not in Simple Icons 1.1.1
    val storybook: ImageVector = SimpleIcons.Storybook

    // ═══════════════════════════════════════════════════════════════════════════
    // LINTERS & FORMATTERS (5)
    // ═══════════════════════════════════════════════════════════════════════════

    val eslint: ImageVector = SimpleIcons.Eslint
    val prettier: ImageVector = SimpleIcons.Prettier
    val biome: ImageVector = Icons.Outlined.Code  // Biome not in Simple Icons 1.1.1
    val stylelint: ImageVector = SimpleIcons.Stylelint
    val editorconfig: ImageVector = Icons.Outlined.Settings  // EditorConfig not in Simple Icons 1.1.1

    // ═══════════════════════════════════════════════════════════════════════════
    // API & DATA (11)
    // ═══════════════════════════════════════════════════════════════════════════

    val graphql: ImageVector = SimpleIcons.Graphql
    val apollographql: ImageVector = SimpleIcons.Apollographql
    val trpc: ImageVector = Icons.Outlined.Code  // tRPC not in Simple Icons 1.1.1
    val swagger: ImageVector = SimpleIcons.Swagger
    val openapi: ImageVector = SimpleIcons.Openapiinitiative
    val postman: ImageVector = SimpleIcons.Postman
    val insomnia: ImageVector = SimpleIcons.Insomnia
    val kafka: ImageVector = SimpleIcons.Apachekafka
    val rabbitmq: ImageVector = SimpleIcons.Rabbitmq
    val grpc: ImageVector = Icons.Outlined.Code  // gRPC not in Simple Icons
    val protobuf: ImageVector = Icons.Outlined.Code  // Protocol Buffers not in Simple Icons

    // ═══════════════════════════════════════════════════════════════════════════
    // AI/ML (5)
    // ═══════════════════════════════════════════════════════════════════════════

    val openai: ImageVector = SimpleIcons.Openai
    val tensorflow: ImageVector = SimpleIcons.Tensorflow
    val pytorch: ImageVector = SimpleIcons.Pytorch
    val huggingface: ImageVector = Icons.Outlined.Code  // Hugging Face not in Simple Icons 1.1.1
    val langchain: ImageVector = Icons.Outlined.Code  // LangChain not in Simple Icons 1.1.1

    // ═══════════════════════════════════════════════════════════════════════════
    // MOBILE (6)
    // ═══════════════════════════════════════════════════════════════════════════

    val android: ImageVector = SimpleIcons.Android
    val ios: ImageVector = SimpleIcons.Apple
    val reactnative: ImageVector = SimpleIcons.React  // React Native uses React icon
    val expo: ImageVector = SimpleIcons.Expo
    val capacitor: ImageVector = SimpleIcons.Capacitor
    val ionic: ImageVector = SimpleIcons.Ionic

    // ═══════════════════════════════════════════════════════════════════════════
    // SHELL (2)
    // ═══════════════════════════════════════════════════════════════════════════

    val gnubash: ImageVector = SimpleIcons.Gnubash
    val powershell: ImageVector = SimpleIcons.Powershell

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA FORMATS (4)
    // ═══════════════════════════════════════════════════════════════════════════

    val json: ImageVector = SimpleIcons.Json
    val yaml: ImageVector = Icons.Outlined.Settings  // YAML not in Simple Icons 1.1.1
    val toml: ImageVector = Icons.Outlined.Settings  // TOML not in Simple Icons 1.1.1
    val markdown: ImageVector = SimpleIcons.Markdown

    // ═══════════════════════════════════════════════════════════════════════════
    // WEB (2)
    // ═══════════════════════════════════════════════════════════════════════════

    val html: ImageVector = SimpleIcons.Html5
    val css: ImageVector = SimpleIcons.Css3

    // Fallback for unknown languages
    val unknown: ImageVector = Icons.Outlined.Code

    // ═══════════════════════════════════════════════════════════════════════════
    // OFFICIAL BRAND COLORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Official brand colors for each language/technology.
     * These are the official colors from the respective brand guidelines.
     */
    object Colors {
        // Programming Languages
        val kotlin = Color(0xFF7F52FF)
        val java = Color(0xFFE76F00)
        val python = Color(0xFF3776AB)
        val javascript = Color(0xFFF7DF1E)
        val typescript = Color(0xFF3178C6)
        val go = Color(0xFF00ADD8)
        val rust = Color(0xFFDEA584)
        val swift = Color(0xFFF05138)
        val cpp = Color(0xFF00599C)
        val c = Color(0xFFA8B9CC)
        val csharp = Color(0xFF512BD4)
        val ruby = Color(0xFFCC342D)
        val php = Color(0xFF777BB4)
        val scala = Color(0xFFDC322F)
        val haskell = Color(0xFF5D4F85)
        val lua = Color(0xFF5C5CFF)       // Dark theme: lighter blue (was #2C2D72)
        val perl = Color(0xFF6B7EB8)       // Dark theme: lighter blue (was #39457E)
        val r = Color(0xFF276DC3)
        val dart = Color(0xFF0175C2)
        val elixir = Color(0xFF9B59B6)      // Dark theme: lighter purple (was #4B275F)
        val clojure = Color(0xFF5881D8)
        val julia = Color(0xFF9558B2)
        val ocaml = Color(0xFFEC6813)
        val zig = Color(0xFFF7A41D)
        val objectivec = Color(0xFF438EFF)
        val fsharp = Color(0xFF378BBA)
        val erlang = Color(0xFFA90533)
        val nim = Color(0xFFFFE953)
        val crystal = Color(0xFFFFFFFF)     // Dark theme: white (was #000000)
        val fortran = Color(0xFF734F96)
        val cobol = Color(0xFF005CA5)
        val assembly = Color(0xFF007AAC)
        val solidity = Color(0xFF8C8C8C)    // Dark theme: lighter gray (was #363636)
        val vlang = Color(0xFF5D87BF)
        val dlang = Color(0xFFB03931)
        val groovy = Color(0xFF4298B8)
        val rescript = Color(0xFFE6484F)
        val racket = Color(0xFF9F1D20)

        // Web Frameworks & Runtimes
        val nodejs = Color(0xFF339933)
        val deno = Color(0xFFFFFFFF)        // Dark theme: white (was #000000)
        val bun = Color(0xFFFBF0DF)
        val spring = Color(0xFF6DB33F)
        val springboot = Color(0xFF6DB33F)
        val django = Color(0xFF44B78B)      // Dark theme: lighter green (was #092E20)
        val flask = Color(0xFFFFFFFF)       // Dark theme: white (was #000000)
        val fastapi = Color(0xFF009688)
        val rails = Color(0xFFCC0000)
        val laravel = Color(0xFFFF2D20)
        val symfony = Color(0xFFFFFFFF)     // Dark theme: white (was #000000)
        val react = Color(0xFF61DAFB)
        val vue = Color(0xFF4FC08D)
        val angular = Color(0xFFDD0031)
        val svelte = Color(0xFFFF3E00)
        val nextjs = Color(0xFFFFFFFF)      // Dark theme: white (was #000000)
        val nuxt = Color(0xFF00DC82)
        val astro = Color(0xFFFF5D01)
        val remix = Color(0xFFFFFFFF)       // Dark theme: white (was #000000)
        val flutter = Color(0xFF02569B)
        val express = Color(0xFFFFFFFF)     // Dark theme: white (was #000000)
        val nestjs = Color(0xFFE0234E)
        val ktor = Color(0xFF087CFA)
        val gin = Color(0xFF00ADD8)
        val phoenix = Color(0xFFFD4F00)
        val blazor = Color(0xFF512BD4)
        val solid = Color(0xFF2C4F7C)
        val lit = Color(0xFF324FFF)
        val qwik = Color(0xFFAC7EF4)
        val htmx = Color(0xFF3366CC)
        val alpinejs = Color(0xFF8BC0D0)
        val emberjs = Color(0xFFE04E39)
        val backbonejs = Color(0xFF0071B5)
        val preact = Color(0xFF673AB8)
        val electron = Color(0xFF47848F)
        val tauri = Color(0xFF24C8D8)
        val fastify = Color(0xFFFFFFFF)     // Dark theme: white (was #000000)

        // CSS Frameworks
        val tailwindcss = Color(0xFF06B6D4)
        val bootstrap = Color(0xFF7952B3)
        val sass = Color(0xFFCC6699)
        val less = Color(0xFF1D365D)
        val stylus = Color(0xFF8C8C8C)      // Dark theme: lighter gray (was #333333)
        val postcss = Color(0xFFDD3A0A)
        val styledcomponents = Color(0xFFDB7093)
        val chakraui = Color(0xFF319795)
        val mui = Color(0xFF007FFF)
        val bulma = Color(0xFF00D1B2)
        val foundation = Color(0xFF14679E)
        val antdesign = Color(0xFF0170FE)

        // Databases
        val postgresql = Color(0xFF4169E1)
        val mysql = Color(0xFF4479A1)
        val mongodb = Color(0xFF47A248)
        val redis = Color(0xFFDC382D)
        val sqlite = Color(0xFF0F80CC)      // Dark theme: lighter blue (was #003B57)
        val mariadb = Color(0xFF00A0B0)     // Dark theme: lighter teal (was #003545)
        val oracle = Color(0xFFF80000)
        val sqlserver = Color(0xFFCC2927)
        val cassandra = Color(0xFF1287B1)
        val couchdb = Color(0xFFE42528)
        val dynamodb = Color(0xFF4053D6)
        val elasticsearch = Color(0xFF005571)
        val neo4j = Color(0xFF4581C3)
        val influxdb = Color(0xFF22ADF6)
        val firebase = Color(0xFFFFCA28)
        val supabase = Color(0xFF3FCF8E)
        val planetscale = Color(0xFFFFFFFF) // Dark theme: white (was #000000)
        val prisma = Color(0xFF2D3748)
        val cockroachdb = Color(0xFF6933FF)
        val duckdb = Color(0xFFFFF000)

        // DevOps & Cloud
        val docker = Color(0xFF2496ED)
        val kubernetes = Color(0xFF326CE5)
        val git = Color(0xFFF05032)
        val github = Color(0xFFFFFFFF)      // Dark theme: white (was #181717)
        val gitlab = Color(0xFFFC6D26)
        val bitbucket = Color(0xFF0052CC)
        val aws = Color(0xFFFF9900)         // Dark theme: AWS Orange (was #232F3E)
        val googlecloud = Color(0xFF4285F4)
        val azure = Color(0xFF0078D4)
        val terraform = Color(0xFF7B42BC)
        val ansible = Color(0xFFEE0000)
        val pulumi = Color(0xFF8A3391)
        val jenkins = Color(0xFFD24939)
        val githubactions = Color(0xFF2088FF)
        val circleci = Color(0xFF8C8C8C)    // Dark theme: lighter gray (was #343434)
        val travisci = Color(0xFF3EAAAF)
        val vercel = Color(0xFFFFFFFF)      // Dark theme: white (was #000000)
        val netlify = Color(0xFF00C7B7)
        val railway = Color(0xFFFFFFFF)     // Dark theme: white (was #0B0D0E)
        val render = Color(0xFF46E3B7)
        val heroku = Color(0xFF430098)
        val digitalocean = Color(0xFF0080FF)
        val cloudflare = Color(0xFFF38020)
        val nginx = Color(0xFF009639)
        val apache = Color(0xFFD22128)

        // Build Tools
        val gradle = Color(0xFF02ACC1)      // Dark theme: lighter teal (was #02303A)
        val maven = Color(0xFFC71A36)
        val npm = Color(0xFFCB3837)
        val yarn = Color(0xFF2C8EBB)
        val pnpm = Color(0xFFF69220)
        val vite = Color(0xFF646CFF)
        val webpack = Color(0xFF8DD6F9)
        val rollup = Color(0xFFEC4A3F)
        val parcel = Color(0xFFE6A07C)
        val esbuild = Color(0xFFFFCF00)
        val swc = Color(0xFFFFFFFF)
        val turborepo = Color(0xFFEF4444)
        val nx = Color(0xFF143055)
        val lerna = Color(0xFF9333EA)
        val bazel = Color(0xFF43A047)
        val cmake = Color(0xFF064F8C)
        val gnumake = Color(0xFFA42E2B)
        val meson = Color(0xFF007EC6)
        val gulp = Color(0xFFCF4647)
        val grunt = Color(0xFFFBA919)
        val sbt = Color(0xFFDC322F)

        // Testing
        val jest = Color(0xFFC21325)
        val vitest = Color(0xFF6E9F18)
        val cypress = Color(0xFF69D3A7)
        val playwright = Color(0xFF2EAD33)
        val selenium = Color(0xFF43B02A)
        val pytest = Color(0xFF0A9EDC)
        val mocha = Color(0xFF8D6748)
        val jasmine = Color(0xFF8A4182)
        val testinglibrary = Color(0xFFE33332)
        val storybook = Color(0xFFFF4785)

        // Linters
        val eslint = Color(0xFF4B32C3)
        val prettier = Color(0xFFF7B93E)
        val biome = Color(0xFF60A5FA)
        val stylelint = Color(0xFF263238)
        val editorconfig = Color(0xFFFEFEFE)

        // API & Data
        val graphql = Color(0xFFE10098)
        val apollographql = Color(0xFF311C87)
        val trpc = Color(0xFF2596BE)
        val swagger = Color(0xFF85EA2D)
        val openapi = Color(0xFF6BA539)
        val postman = Color(0xFFFF6C37)
        val insomnia = Color(0xFF4000BF)
        val kafka = Color(0xFFFFFFFF)       // Dark theme: white (was #231F20)
        val rabbitmq = Color(0xFFFF6600)
        val grpc = Color(0xFF244C5A)
        val protobuf = Color(0xFF4285F4)

        // AI/ML
        val openai = Color(0xFF412991)
        val tensorflow = Color(0xFFFF6F00)
        val pytorch = Color(0xFFEE4C2C)
        val huggingface = Color(0xFFFFD21E)
        val langchain = Color(0xFF3FCF8E)   // Dark theme: green (was #1C3C3C)

        // Mobile
        val android = Color(0xFF34A853)
        val ios = Color(0xFFFFFFFF)         // Dark theme: white (was #000000)
        val reactnative = Color(0xFF61DAFB)
        val expo = Color(0xFFFFFFFF)        // Dark theme: white (was #000020)
        val capacitor = Color(0xFF119EFF)
        val ionic = Color(0xFF3880FF)

        // Shell
        val gnubash = Color(0xFF4EAA25)
        val powershell = Color(0xFF5391FE)

        // Data formats
        val json = Color(0xFFCBCB41)        // Dark theme: JSON yellow (was #000000)
        val yaml = Color(0xFFCB171E)
        val toml = Color(0xFF9C4121)
        val markdown = Color(0xFFFFFFFF)    // Dark theme: white (was #000000)

        // Web
        val html = Color(0xFFE34F26)
        val css = Color(0xFF1572B6)

        // Fallback
        val unknown = Color(0xFF808080)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOKUP FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get icon and color for a file extension.
     * @param extension File extension without the dot (e.g., "kt", "py", "js")
     * @return Pair of ImageVector icon and Color
     */
    fun forExtension(extension: String): Pair<ImageVector, Color> {
        return when (extension.lowercase()) {
            // Kotlin
            "kt", "kts" -> kotlin to Colors.kotlin

            // Java
            "java" -> java to Colors.java

            // Python
            "py", "pyw", "pyx", "pxd", "pxi" -> python to Colors.python

            // JavaScript
            "js", "mjs", "cjs" -> javascript to Colors.javascript

            // TypeScript
            "ts", "mts", "cts" -> typescript to Colors.typescript
            "tsx" -> react to Colors.react
            "jsx" -> react to Colors.react

            // Go
            "go" -> go to Colors.go

            // Rust
            "rs" -> rust to Colors.rust

            // Swift
            "swift" -> swift to Colors.swift

            // Objective-C
            "m", "mm" -> objectivec to Colors.objectivec

            // C/C++
            "c", "h" -> c to Colors.c
            "cpp", "cc", "cxx", "hpp", "hxx", "hh" -> cpp to Colors.cpp

            // C#
            "cs" -> csharp to Colors.csharp

            // F#
            "fs", "fsx", "fsi" -> fsharp to Colors.fsharp

            // Ruby
            "rb", "erb", "rake" -> ruby to Colors.ruby

            // PHP
            "php" -> php to Colors.php

            // Scala
            "scala", "sc" -> scala to Colors.scala

            // Haskell
            "hs", "lhs" -> haskell to Colors.haskell

            // Lua
            "lua" -> lua to Colors.lua

            // Perl
            "pl", "pm" -> perl to Colors.perl

            // R
            "r", "rmd" -> r to Colors.r

            // Dart
            "dart" -> dart to Colors.dart

            // Elixir
            "ex", "exs" -> elixir to Colors.elixir

            // Erlang
            "erl", "hrl" -> erlang to Colors.erlang

            // Clojure
            "clj", "cljs", "cljc", "edn" -> clojure to Colors.clojure

            // Julia
            "jl" -> julia to Colors.julia

            // OCaml
            "ml", "mli" -> ocaml to Colors.ocaml

            // Zig
            "zig" -> zig to Colors.zig

            // Nim
            "nim", "nims" -> nim to Colors.nim

            // Crystal
            "cr" -> crystal to Colors.crystal

            // Fortran
            "f", "f90", "f95", "f03", "f08", "for" -> fortran to Colors.fortran

            // COBOL
            "cob", "cbl" -> cobol to Colors.cobol

            // Assembly
            "asm", "s" -> assembly to Colors.assembly

            // Solidity
            "sol" -> solidity to Colors.solidity

            // V
            "v" -> vlang to Colors.vlang

            // D
            "d" -> dlang to Colors.dlang

            // Groovy
            "groovy", "gvy", "gy", "gsh" -> groovy to Colors.groovy

            // ReScript
            "res", "resi" -> rescript to Colors.rescript

            // Racket
            "rkt" -> racket to Colors.racket

            // HTML
            "html", "htm", "xhtml" -> html to Colors.html

            // CSS
            "css" -> css to Colors.css
            "scss", "sass" -> sass to Colors.sass
            "less" -> less to Colors.less
            "styl" -> stylus to Colors.stylus

            // Vue
            "vue" -> vue to Colors.vue

            // Svelte
            "svelte" -> svelte to Colors.svelte

            // Astro
            "astro" -> astro to Colors.astro

            // Data formats
            "json" -> json to Colors.json
            "yaml", "yml" -> yaml to Colors.yaml
            "toml" -> toml to Colors.toml
            "md", "markdown" -> markdown to Colors.markdown

            // GraphQL
            "graphql", "gql" -> graphql to Colors.graphql

            // Protocol Buffers
            "proto" -> protobuf to Colors.protobuf

            // Prisma
            "prisma" -> prisma to Colors.prisma

            // Config files
            "gradle" -> gradle to Colors.gradle
            "xml", "pom" -> maven to Colors.maven

            // Shell
            "sh", "bash", "zsh" -> gnubash to Colors.gnubash
            "ps1", "psm1", "psd1" -> powershell to Colors.powershell

            // Docker
            "dockerfile" -> docker to Colors.docker

            // Git
            "gitignore", "gitattributes", "gitmodules" -> git to Colors.git

            // Database
            "sql" -> postgresql to Colors.postgresql

            // Config
            "eslintrc", "eslintignore" -> eslint to Colors.eslint
            "prettierrc", "prettierignore" -> prettier to Colors.prettier

            else -> unknown to Colors.unknown
        }
    }

    /**
     * Get icon and color for a language name.
     * @param language Language name (case-insensitive)
     * @return Pair of ImageVector icon and Color
     */
    fun forLanguage(language: String): Pair<ImageVector, Color> {
        return when (language.lowercase()) {
            "kotlin" -> kotlin to Colors.kotlin
            "java" -> java to Colors.java
            "python", "python3" -> python to Colors.python
            "javascript", "js" -> javascript to Colors.javascript
            "typescript", "ts" -> typescript to Colors.typescript
            "go", "golang" -> go to Colors.go
            "rust" -> rust to Colors.rust
            "swift" -> swift to Colors.swift
            "objective-c", "objectivec", "objc" -> objectivec to Colors.objectivec
            "c++", "cpp", "cxx" -> cpp to Colors.cpp
            "c" -> c to Colors.c
            "c#", "csharp" -> csharp to Colors.csharp
            "f#", "fsharp" -> fsharp to Colors.fsharp
            "ruby" -> ruby to Colors.ruby
            "php" -> php to Colors.php
            "scala" -> scala to Colors.scala
            "haskell" -> haskell to Colors.haskell
            "lua" -> lua to Colors.lua
            "perl" -> perl to Colors.perl
            "r" -> r to Colors.r
            "dart" -> dart to Colors.dart
            "elixir" -> elixir to Colors.elixir
            "erlang" -> erlang to Colors.erlang
            "clojure" -> clojure to Colors.clojure
            "julia" -> julia to Colors.julia
            "ocaml" -> ocaml to Colors.ocaml
            "zig" -> zig to Colors.zig
            "nim" -> nim to Colors.nim
            "crystal" -> crystal to Colors.crystal
            "fortran" -> fortran to Colors.fortran
            "cobol" -> cobol to Colors.cobol
            "assembly", "asm" -> assembly to Colors.assembly
            "solidity" -> solidity to Colors.solidity
            "v", "vlang" -> vlang to Colors.vlang
            "d", "dlang" -> dlang to Colors.dlang
            "groovy" -> groovy to Colors.groovy
            "rescript" -> rescript to Colors.rescript
            "racket" -> racket to Colors.racket
            "node", "nodejs", "node.js" -> nodejs to Colors.nodejs
            "deno" -> deno to Colors.deno
            "bun" -> bun to Colors.bun
            "html" -> html to Colors.html
            "css" -> css to Colors.css
            "sass", "scss" -> sass to Colors.sass
            "less" -> less to Colors.less
            "bash", "shell", "sh", "zsh" -> gnubash to Colors.gnubash
            "powershell" -> powershell to Colors.powershell
            else -> unknown to Colors.unknown
        }
    }

    /**
     * Get icon and color for a framework/runtime name.
     * @param framework Framework name (case-insensitive)
     * @return Pair of ImageVector icon and Color
     */
    fun forFramework(framework: String): Pair<ImageVector, Color> {
        return when (framework.lowercase()) {
            // Backend Frameworks
            "spring", "spring boot", "springboot" -> spring to Colors.spring
            "django" -> django to Colors.django
            "flask" -> flask to Colors.flask
            "fastapi" -> fastapi to Colors.fastapi
            "rails", "ruby on rails" -> rails to Colors.rails
            "laravel" -> laravel to Colors.laravel
            "symfony" -> symfony to Colors.symfony
            "express", "expressjs" -> express to Colors.express
            "nestjs", "nest" -> nestjs to Colors.nestjs
            "ktor" -> ktor to Colors.ktor
            "gin" -> gin to Colors.gin
            "phoenix" -> phoenix to Colors.phoenix
            "fastify" -> fastify to Colors.fastify

            // Frontend Frameworks
            "react", "reactjs" -> react to Colors.react
            "vue", "vuejs", "vue.js" -> vue to Colors.vue
            "angular" -> angular to Colors.angular
            "svelte" -> svelte to Colors.svelte
            "next", "nextjs", "next.js" -> nextjs to Colors.nextjs
            "nuxt", "nuxtjs", "nuxt.js" -> nuxt to Colors.nuxt
            "astro" -> astro to Colors.astro
            "remix" -> remix to Colors.remix
            "solid", "solidjs" -> solid to Colors.solid
            "lit" -> lit to Colors.lit
            "qwik" -> qwik to Colors.qwik
            "htmx" -> htmx to Colors.htmx
            "alpine", "alpinejs", "alpine.js" -> alpinejs to Colors.alpinejs
            "ember", "emberjs", "ember.js" -> emberjs to Colors.emberjs
            "backbone", "backbonejs", "backbone.js" -> backbonejs to Colors.backbonejs
            "preact" -> preact to Colors.preact
            "blazor" -> blazor to Colors.blazor

            // Mobile/Desktop
            "flutter" -> flutter to Colors.flutter
            "electron" -> electron to Colors.electron
            "tauri" -> tauri to Colors.tauri
            "react native", "reactnative" -> reactnative to Colors.reactnative
            "expo" -> expo to Colors.expo
            "capacitor" -> capacitor to Colors.capacitor
            "ionic" -> ionic to Colors.ionic

            // CSS Frameworks
            "tailwind", "tailwindcss" -> tailwindcss to Colors.tailwindcss
            "bootstrap" -> bootstrap to Colors.bootstrap
            "chakra", "chakraui" -> chakraui to Colors.chakraui
            "material ui", "mui" -> mui to Colors.mui
            "bulma" -> bulma to Colors.bulma
            "foundation" -> foundation to Colors.foundation
            "ant design", "antdesign" -> antdesign to Colors.antdesign

            // Build Tools
            "gradle" -> gradle to Colors.gradle
            "maven" -> maven to Colors.maven
            "npm" -> npm to Colors.npm
            "yarn" -> yarn to Colors.yarn
            "pnpm" -> pnpm to Colors.pnpm
            "vite" -> vite to Colors.vite
            "webpack" -> webpack to Colors.webpack
            "rollup" -> rollup to Colors.rollup
            "parcel" -> parcel to Colors.parcel
            "esbuild" -> esbuild to Colors.esbuild
            "swc" -> swc to Colors.swc
            "turborepo" -> turborepo to Colors.turborepo
            "nx" -> nx to Colors.nx

            // DevOps
            "docker" -> docker to Colors.docker
            "kubernetes", "k8s" -> kubernetes to Colors.kubernetes
            "terraform" -> terraform to Colors.terraform
            "ansible" -> ansible to Colors.ansible

            else -> unknown to Colors.unknown
        }
    }

    /**
     * Get icon and color for a database name.
     * @param database Database name (case-insensitive)
     * @return Pair of ImageVector icon and Color
     */
    fun forDatabase(database: String): Pair<ImageVector, Color> {
        return when (database.lowercase()) {
            "postgresql", "postgres" -> postgresql to Colors.postgresql
            "mysql" -> mysql to Colors.mysql
            "mongodb", "mongo" -> mongodb to Colors.mongodb
            "redis" -> redis to Colors.redis
            "sqlite" -> sqlite to Colors.sqlite
            "mariadb" -> mariadb to Colors.mariadb
            "oracle" -> oracle to Colors.oracle
            "sqlserver", "mssql", "sql server" -> sqlserver to Colors.sqlserver
            "cassandra" -> cassandra to Colors.cassandra
            "couchdb", "couch" -> couchdb to Colors.couchdb
            "dynamodb", "dynamo" -> dynamodb to Colors.dynamodb
            "elasticsearch", "elastic" -> elasticsearch to Colors.elasticsearch
            "neo4j" -> neo4j to Colors.neo4j
            "influxdb", "influx" -> influxdb to Colors.influxdb
            "firebase" -> firebase to Colors.firebase
            "supabase" -> supabase to Colors.supabase
            "planetscale" -> planetscale to Colors.planetscale
            "prisma" -> prisma to Colors.prisma
            "cockroachdb", "cockroach" -> cockroachdb to Colors.cockroachdb
            "duckdb", "duck" -> duckdb to Colors.duckdb
            else -> unknown to Colors.unknown
        }
    }

    /**
     * Get icon and color for a cloud platform name.
     * @param cloud Cloud platform name (case-insensitive)
     * @return Pair of ImageVector icon and Color
     */
    fun forCloud(cloud: String): Pair<ImageVector, Color> {
        return when (cloud.lowercase()) {
            "aws", "amazon" -> aws to Colors.aws
            "gcp", "google cloud", "googlecloud" -> googlecloud to Colors.googlecloud
            "azure", "microsoft azure" -> azure to Colors.azure
            "vercel" -> vercel to Colors.vercel
            "netlify" -> netlify to Colors.netlify
            "railway" -> railway to Colors.railway
            "render" -> render to Colors.render
            "heroku" -> heroku to Colors.heroku
            "digitalocean", "do" -> digitalocean to Colors.digitalocean
            "cloudflare" -> cloudflare to Colors.cloudflare
            else -> unknown to Colors.unknown
        }
    }

    /**
     * Get icon and color for a testing framework name.
     * @param testing Testing framework name (case-insensitive)
     * @return Pair of ImageVector icon and Color
     */
    fun forTesting(testing: String): Pair<ImageVector, Color> {
        return when (testing.lowercase()) {
            "jest" -> jest to Colors.jest
            "vitest" -> vitest to Colors.vitest
            "cypress" -> cypress to Colors.cypress
            "playwright" -> playwright to Colors.playwright
            "selenium" -> selenium to Colors.selenium
            "pytest" -> pytest to Colors.pytest
            "mocha" -> mocha to Colors.mocha
            "jasmine" -> jasmine to Colors.jasmine
            "testing library", "testinglibrary" -> testinglibrary to Colors.testinglibrary
            "storybook" -> storybook to Colors.storybook
            else -> unknown to Colors.unknown
        }
    }

    /**
     * Get icon and color for an AI/ML framework name.
     * @param aiml AI/ML framework name (case-insensitive)
     * @return Pair of ImageVector icon and Color
     */
    fun forAI(aiml: String): Pair<ImageVector, Color> {
        return when (aiml.lowercase()) {
            "openai", "gpt", "chatgpt" -> openai to Colors.openai
            "tensorflow", "tf" -> tensorflow to Colors.tensorflow
            "pytorch", "torch" -> pytorch to Colors.pytorch
            "huggingface", "hugging face" -> huggingface to Colors.huggingface
            "langchain" -> langchain to Colors.langchain
            else -> unknown to Colors.unknown
        }
    }
}
