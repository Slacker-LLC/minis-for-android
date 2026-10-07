package com.openminis.app.runtime.ubuntu

import com.openminis.app.util.shellQuote

/**
 * What the guest command `minis-apt` may ask the host to do as Root inside the Ubuntu rootfs: update the
 * package lists, install packages, remove packages. Nothing else, and no raw command: the guest names
 * packages, the App builds the whole command.
 *
 * The guest runs as the App's own user and cannot use apt itself. A package install needs Root, so the
 * App does that one job on the guest's behalf, in a throw-away mount namespace like the provisioner
 * ([UbuntuProvisioner.wrapInRootfsNamespace]), from the Ubuntu archive only. Package names are checked
 * against a strict pattern, so a name can never carry an apt option, a version or release pin, a path
 * or a shell character; removal is refused when apt's own simulation says it would take out a package
 * the runtime needs.
 */
internal object AptCommandPolicy {
    const val MAX_PACKAGES = 20

    /** A Debian package name: lowercase letters, digits, `+ - .`, starting with a letter or digit. */
    val PACKAGE_NAME = Regex("^[a-z0-9][a-z0-9+.-]{0,62}$")

    sealed interface Request {
        data object Update : Request
        data class Install(val packages: List<String>) : Request
        data class Remove(val packages: List<String>) : Request
    }

    sealed interface Parsed {
        data class Ok(val request: Request) : Parsed
        data class Refused(val message: String) : Parsed
    }

    const val USAGE = "usage: minis-apt update | install <package>... | remove <package>...  " +
        "(Ubuntu archive packages; runs as root in the Ubuntu environment)"

    /** Packages whose removal would break the runtime, on top of what apt itself protects as Essential. */
    internal val PROTECTED: Set<String> = (UbuntuProvisioner.BASE_PACKAGES + listOf(
        "apt", "dpkg", "bash", "coreutils", "libc6", "base-files", "ubuntu-minimal", "ubuntu-standard",
        "ubuntu-keyring", "passwd", "login", "util-linux", "perl-base", "tar", "sed", "grep", "findutils",
    )).toSet()

    fun parse(args: List<String>): Parsed {
        val verb = args.firstOrNull() ?: return Parsed.Refused(USAGE)
        val names = args.drop(1)
        if (verb == "update") {
            return if (names.isEmpty()) Parsed.Ok(Request.Update) else Parsed.Refused("update takes no arguments. $USAGE")
        }
        if (verb != "install" && verb != "remove") return Parsed.Refused("unknown command '$verb'. $USAGE")
        if (names.isEmpty()) return Parsed.Refused("$verb needs at least one package name. $USAGE")
        if (names.size > MAX_PACKAGES) return Parsed.Refused("at most $MAX_PACKAGES packages per call")
        names.firstOrNull { !PACKAGE_NAME.matches(it) }?.let {
            return Parsed.Refused(
                "'${it.take(64)}' is not a plain package name (lowercase letters, digits, + - . only; " +
                    "no options, versions or paths)",
            )
        }
        val distinct = names.distinct()
        return Parsed.Ok(if (verb == "install") Request.Install(distinct) else Request.Remove(distinct))
    }

    /** Exit code of the guest script when a removal would take out a package the runtime needs. */
    const val EXIT_PROTECTED = 77

    /** The bash script run as Root inside the rootfs for [request]. [proxy] is the helper URI, or blank. */
    fun guestScript(request: Request, proxy: String): String {
        val aptProxy = if (proxy.isBlank()) {
            // Without the helper, go direct and say so: an older runtime left /etc/apt/apt.conf.d/99minis-proxy
            // pointing at a helper address that now wants credentials, and apt would use it and fail with 407.
            " -o Acquire::http::Proxy=DIRECT -o Acquire::https::Proxy=DIRECT"
        } else {
            " -o ${shellQuote("Acquire::http::Proxy=$proxy")}" +
                " -o ${shellQuote("Acquire::https::Proxy=$proxy")}"
        }
        val apt = "/usr/bin/apt-get -o APT::Sandbox::User=root$aptProxy" +
            " -o Acquire::Retries=1 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30"
        return buildString {
            appendLine("set -eu")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            when (request) {
                Request.Update -> appendLine("$apt update")
                is Request.Install -> {
                    appendLine("$apt install -y --no-install-recommends ${request.packages.joinToString(" ")}")
                }
                is Request.Remove -> {
                    val packages = request.packages.joinToString(" ")
                    // apt's own simulation lists every package the removal would take out, dependents
                    // included; refuse before anything is removed if one of them is needed by the runtime.
                    val protectedPattern = PROTECTED.sorted().joinToString("|") {
                        it.replace("+", "\\+").replace(".", "\\.")
                    }
                    appendLine("sim=\"\$($apt -s remove $packages 2>&1)\" || { printf '%s\\n' \"\$sim\"; exit 1; }")
                    appendLine("if printf '%s\\n' \"\$sim\" | grep -Eq '^Remv ($protectedPattern)( |\$)'; then")
                    appendLine("  echo 'minis-apt: refused: removing this would also remove packages the Ubuntu runtime needs' >&2")
                    appendLine("  exit $EXIT_PROTECTED")
                    appendLine("fi")
                    appendLine("$apt remove -y $packages")
                }
            }
        }
    }
}
