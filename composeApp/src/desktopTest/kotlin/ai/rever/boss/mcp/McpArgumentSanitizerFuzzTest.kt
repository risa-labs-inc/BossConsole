package ai.rever.boss.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract of [McpArgumentSanitizer], stated as a property over generated input rather than
 * as one example per bug report: a credential carried in any of the shapes below, wrapped in any
 * of the contexts below, never reaches the approval dialog or the ledger, and a command that
 * carries no credential comes out byte-identical, because the operator approves what they can
 * read.
 *
 * Each shape is a way a real command or file labels a secret (an assignment, a header, a flag, a
 * URI authority, a JSON body, a vendor-prefixed token); each context is a way the same text is
 * wrapped on its way through a tool argument (quotes, `export`, a JSON string with its escapes,
 * a chained command line, a nested argument object). The four sanitizer issues so far (#836,
 * #837, #886 and this) were each one cell of that table found by hand; this walks the table.
 *
 * The generator is seeded, so a failure prints its seed and the exact input, and a shape or
 * context is added by adding a line, not a test.
 */
class McpArgumentSanitizerFuzzTest {
    /** A value that cannot occur by accident in the surrounding text: letters and digits only. */
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

    private fun Random.secret(length: Int = nextInt(12, 40)): String =
        buildString { repeat(length) { append(alphabet[nextInt(alphabet.length)]) } }

    private fun interface Shape {
        fun render(secret: String): String
    }

    private fun interface Context {
        fun wrap(text: String): String
    }

    /** How a secret is labelled. Each entry is one real command line, config line or request body. */
    private val shapes: List<Pair<String, Shape>> =
        listOf(
            "env assignment" to Shape { "export DB_PASSWORD=$it" },
            "env assignment, suffixed name" to Shape { "export AWS_SECRET_ACCESS_KEY=$it" },
            "env assignment, secret prefix" to Shape { "SECRET_KEY_BASE=$it rails server" },
            "env assignment, quoted" to Shape { "TOKEN=\"$it\" ./run.sh" },
            "yaml-ish key" to Shape { "password: $it" },
            "json body" to Shape { "curl -d '{\"password\":\"$it\"}' https://api.example.invalid/login" },
            "json body, spaced" to Shape { "curl -d '{\"api_key\": \"$it\"}' https://api.example.invalid" },
            "json header object" to Shape { "{\"Authorization\":\"Basic $it\"}" },
            "authorization bearer" to Shape { "curl -H 'Authorization: Bearer $it' https://api.example.invalid" },
            "authorization basic" to Shape { "curl -H \"Authorization: Basic $it\" https://api.example.invalid" },
            "custom api key header" to Shape { "curl -H 'X-API-Key: $it' https://api.example.invalid" },
            "cookie header" to Shape { "curl -H 'Cookie: session=$it; theme=dark' https://app.example.invalid" },
            "long flag with equals" to Shape { "mysql --password=$it -e 'select 1'" },
            "long flag with space" to Shape { "mysql --password $it -e 'select 1'" },
            "token flag with space" to Shape { "vault login --token $it" },
            "docker login" to Shape { "docker login -u deploy --password $it registry.example.invalid" },
            "sshpass" to Shape { "sshpass -p $it ssh deploy@host.example.invalid" },
            "curl basic auth" to Shape { "curl -u admin:$it https://api.example.invalid/health" },
            "uri userinfo" to Shape { "psql postgres://admin:$it@db.example.invalid/app" },
            "uri userinfo, git" to Shape { "git clone https://oauth2:$it@git.example.invalid/org/repo.git" },
            "npm auth token" to Shape { "npm config set //registry.npmjs.org/:_authToken $it" },
            "github token" to Shape { "gh auth login --with-token <<< ghp_$it" },
            "vendor sk key" to Shape { "export OPENAI_API_KEY=sk-$it" },
            "jwt" to Shape { "curl -H 'X-Auth: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.$it'" },
            "aws access key id" to Shape { "aws configure set aws_access_key_id AKIA${awsKeyBody(it)}" },
            "pem block" to
                Shape { "printf '%s' '-----BEGIN RSA PRIVATE KEY-----\n$it\n-----END RSA PRIVATE KEY-----' > id_rsa" },
        )

    /** How the same text is wrapped by the time it is a tool argument. */
    private val contexts: List<Pair<String, Context>> =
        listOf(
            "plain" to Context { it },
            "single quoted" to Context { "sh -c '$it'" },
            "double quoted" to Context { "bash -lc \"$it\"" },
            "chained after another command" to Context { "cd /srv/app && $it" },
            "chained before another command" to Context { "$it; echo done" },
            "piped" to Context { "$it | tee /tmp/out.log" },
            "multiline script" to Context { "#!/bin/sh\nset -e\n$it\necho finished\n" },
            "json string value" to Context { jsonString(it) },
            "surrounded by prose" to Context { "Please run the following and report back: $it (this is important)" },
            "unicode neighbours" to Context { "ünïcödé → $it ← 終わり" },
        )

    /** The documented shape is 16 upper-case alphanumerics after the prefix; the secret supplies them. */
    private fun awsKeyBody(secret: String): String {
        val body = secret.uppercase().filter { it.isLetterOrDigit() }
        return body.take(16).padEnd(16, '7')
    }

    private fun jsonString(text: String): String = Json.encodeToString(kotlinx.serialization.serializer<String>(), text)

    private fun sanitizedCommand(text: String): String {
        val sanitized = McpArgumentSanitizer.sanitize(mapOf("command" to text))
        return sanitized["command"]!!
    }

    @Test
    fun `no credential shape survives any context`() {
        val seed = System.nanoTime()
        val random = Random(seed)
        val failures = mutableListOf<String>()
        repeat(3) {
            for ((shapeName, shape) in shapes) {
                for ((contextName, context) in contexts) {
                    val secret = random.secret()
                    val input = context.wrap(shape.render(secret))
                    val output = sanitizedCommand(input)
                    if (output.contains(secret)) {
                        val shown = input.replace("\n", "\\n")
                        val got = output.replace("\n", "\\n")
                        failures += "$shapeName / $contextName\n    in:  $shown\n    out: $got"
                    }
                }
            }
        }
        val report = failures.distinct().joinToString("\n")
        assertTrue(failures.isEmpty(), "seed=$seed; ${failures.size} leaking cell(s):\n$report")
    }

    @Test
    fun `a credential survives nowhere in a nested argument either`() {
        val random = Random(20260919)
        for ((shapeName, shape) in shapes) {
            val secret = random.secret()
            val rendered = shape.render(secret)
            val nested =
                mapOf(
                    "steps" to listOf(mapOf("run" to rendered), "echo ok"),
                    "config" to mapOf("script" to listOf(rendered)),
                    "raw" to Json.parseToJsonElement("""{"cmd": ${jsonString(rendered)}}""") as JsonObject,
                )
            val out = McpArgumentSanitizer.sanitize(nested).values.joinToString("\n")
            assertTrue(!out.contains(secret), "$shapeName leaked through a nested argument:\n$out")
        }
    }

    /**
     * The other half of the contract. Every line here is a command an operator must be able to
     * read in the dialog exactly as the agent wrote it; a rule that touches any of them hides the
     * thing approval exists to show.
     */
    private val benign =
        listOf(
            "git push -u origin main",
            "git log --author=alice@example.com --oneline",
            "python -u train.py --epochs 3",
            "curl -u admin https://api.example.invalid/health",
            "open https://example.com/@handle/status/1",
            "docker run -p 8080:80 nginx",
            "ssh -p 2222 deploy@host.example.invalid",
            "grep -rn 'password' src/ --include='*.kt'",
            "cat docs/tokens.md",
            "echo 'the secret to good tea is patience'",
            "npm token list",
            "kubectl get secret -n default",
            "aws s3 ls s3://bucket/keys/",
            "ls ~/.ssh",
            "cat '-----BEGIN PUBLIC KEY-----MFkw' > pub",
            "export PATH=/usr/local/bin:\$PATH",
            "export EDITOR=vim",
            "redis-cli -u redis://cache.example.invalid:6379 ping",
            "psql postgres://db.example.invalid/app -c 'select 1'",
            "make -j4 && ./gradlew test",
        )

    @Test
    fun `a command without a credential is untouched`() {
        for (line in benign) {
            for ((_, context) in contexts) {
                val input = context.wrap(line)
                assertEquals(input, sanitizedCommand(input), "a benign command was altered")
            }
        }
    }
}
