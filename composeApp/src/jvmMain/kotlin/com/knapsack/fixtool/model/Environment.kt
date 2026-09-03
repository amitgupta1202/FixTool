package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * **Where a counterparty is today**, as distinct from **who** it is.
 *
 * A profile answers both questions at once, and that is why a desk ends up with `UAT1-BuySide`,
 * `QA1-BuySide` and `DEV1-BuySide`: three profiles carrying one counterparty's CompIDs, differing
 * only in a host, a port and a TLS flag. Every rule change has to be made three times, and the day
 * one of them is missed is the day a test passes against the wrong environment.
 *
 * So an environment is the endpoint half on its own, and a connection is a counterparty **times** an
 * environment. A scenario names only the counterparty, which is what makes the same flow runnable
 * against UAT and QA without a copy of the scenario per environment.
 *
 * ### The session qualifier is the environment's name, and that is load-bearing
 *
 * QuickFIX/J keys its sequence-number store on BeginString, SenderCompID, TargetCompID and the
 * qualifier. Two environments reached by one counterparty are the same first three, so without a
 * qualifier they share a store and each logon fights the other's sequence numbers. Naming the
 * qualifier after the environment makes that impossible to get wrong by hand — which is exactly the
 * bug found in this repo's own saved profiles, where the UAT1 pair carried the qualifier `QA1`.
 *
 * ### Empty means "leave it alone"
 *
 * Every field is an override and a blank one is not applied, so an environment that only moves the
 * port is a legal environment. The alternative — a full endpoint per environment — would mean every
 * environment repeating the fields that never differ.
 */
@Serializable
data class Environment(
    val name: String,
    val host: String = "",
    val port: String = "",
    /** Null leaves the profile's own TLS setting alone; a value overrides it. */
    val useSSL: Boolean? = null,
    val autoReconnect: Boolean? = null,
) {
    /**
     * The profile's config as it should be for this environment.
     *
     * `socketConnectHost` follows `host` because that is the field QuickFIX/J actually dials when it
     * is set, and a profile whose two host fields disagreed would connect to the old environment
     * while showing the new one.
     */
    fun applyTo(config: FixConnectionConfig): FixConnectionConfig =
        config.copy(
            host = host.ifBlank { config.host },
            socketConnectHost = host.ifBlank { config.socketConnectHost },
            port = port.ifBlank { config.port },
            useSSL = useSSL ?: config.useSSL,
            autoReconnect = autoReconnect ?: config.autoReconnect,
            sessionQualifier = name,
        )

    /** True when this environment says nothing at all, which is a name and no endpoint. */
    val isEmpty: Boolean
        get() = host.isBlank() && port.isBlank() && useSSL == null && autoReconnect == null

    companion object {
        /** What the endpoint half of [config] looks like as an environment called [name]. */
        fun of(
            name: String,
            config: FixConnectionConfig,
        ): Environment =
            Environment(
                name = name,
                host = config.socketConnectHost.ifBlank { config.host },
                port = config.port,
                useSSL = config.useSSL,
                autoReconnect = config.autoReconnect,
            )
    }
}
