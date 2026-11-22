/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

/** How Dogo talks to an agent. */
enum class AgentKind {
    /** A GGUF model run in-process with llama.cpp. */
    ON_DEVICE,

    /** Any server speaking the OpenAI `/v1/chat/completions` API. */
    OPENAI_COMPATIBLE,

    /** Anthropic's Messages API. */
    ANTHROPIC,

    /** A command-line agent run inside Termux ([TermuxCli]). */
    TERMUX_CLI,
}

/** Whether an agent needs an API key. */
enum class KeyRequirement { NONE, OPTIONAL, REQUIRED }

/**
 * One choice in the agent picker.
 *
 * The defaults are only a starting point: every remote agent's address, model
 * and key can be changed by the user, which is what lets one preset cover a
 * server on a laptop, in Termux, or behind a tunnel.
 */
data class AgentPreset(
    val id: String,
    val displayName: String,
    val kind: AgentKind,
    val blurb: String,
    val defaultBaseUrl: String = "",
    val defaultModel: String = "",
    val key: KeyRequirement = KeyRequirement.NONE,
    val keyHint: String = "",
) {
    /**
     * True when the conversation leaves Dogo for a server. A Termux CLI agent
     * runs on the phone but usually calls a hosted model, so it counts.
     */
    val leavesDevice: Boolean
        get() = kind != AgentKind.ON_DEVICE

    /**
     * Whether photos can be sent. HTTP agents can carry them; whether the model
     * behind them can actually see is up to the server (Hermes, GPT, Claude and
     * Gemini can). The on-device model and Termux CLIs only take text.
     */
    val seesImages: Boolean
        get() = kind == AgentKind.OPENAI_COMPATIBLE || kind == AgentKind.ANTHROPIC
}

/**
 * The agents Dogo can use, in picker order.
 *
 * Like Omarchy, Dogo doesn't push one vendor: on-device is the default because
 * it works offline and keeps conversations on the phone, and everything else is
 * one tap away.
 */
object AgentCatalog {

    val ON_DEVICE = AgentPreset(
        id = "on-device",
        displayName = "On this phone",
        kind = AgentKind.ON_DEVICE,
        blurb = "Runs a downloaded model with llama.cpp. Private and works offline, " +
            "but slow on older phones.",
    )

    val HERMES = AgentPreset(
        id = "hermes",
        displayName = "Hermes Agent",
        kind = AgentKind.OPENAI_COMPATIBLE,
        blurb = "Nous Research's agent on your computer, through its API server. " +
            "Connect over USB and run on the computer: adb reverse tcp:8642 tcp:8642",
        defaultBaseUrl = "http://127.0.0.1:8642/v1",
        defaultModel = "hermes-agent",
        key = KeyRequirement.REQUIRED,
        keyHint = "API_SERVER_KEY from ~/.hermes/.env",
    )

    val OPENROUTER = AgentPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        kind = AgentKind.OPENAI_COMPATIBLE,
        blurb = "Hundreds of hosted models behind one key.",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openrouter/auto",
        key = KeyRequirement.REQUIRED,
        keyHint = "openrouter.ai/keys",
    )

    val GROQ = AgentPreset(
        id = "groq",
        displayName = "Groq",
        kind = AgentKind.OPENAI_COMPATIBLE,
        blurb = "Fast hosted open models. Test the connection to pick one.",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        key = KeyRequirement.REQUIRED,
        keyHint = "console.groq.com/keys",
    )

    val ANTHROPIC = AgentPreset(
        id = "anthropic",
        displayName = "Claude (Anthropic)",
        kind = AgentKind.ANTHROPIC,
        blurb = "Anthropic's Claude models. Test the connection to pick one.",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        key = KeyRequirement.REQUIRED,
        keyHint = "console.anthropic.com",
    )

    val OLLAMA = AgentPreset(
        id = "ollama",
        displayName = "Ollama",
        kind = AgentKind.OPENAI_COMPATIBLE,
        blurb = "A model server you run yourself, for example in Termux on this phone.",
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        key = KeyRequirement.OPTIONAL,
    )

    val CUSTOM = AgentPreset(
        id = "custom",
        displayName = "Custom (OpenAI-compatible)",
        kind = AgentKind.OPENAI_COMPATIBLE,
        blurb = "Any server with an OpenAI-style /v1/chat/completions endpoint: " +
            "LM Studio, llama-server, vLLM, LiteLLM, DeepSeek and others.",
        key = KeyRequirement.OPTIONAL,
    )

    /** Omarchy-style: run a CLI agent the user installed and signed in to in Termux. */
    val TERMUX = AgentPreset(
        id = "termux-cli",
        displayName = "CLI agent in Termux",
        kind = AgentKind.TERMUX_CLI,
        blurb = "Claude Code, OpenCode, Codex or Hermes installed in Termux, signed in with your own account.",
        defaultModel = TermuxCli.CLAUDE.id,
    )

    val all: List<AgentPreset> = listOf(ON_DEVICE, HERMES, OPENROUTER, GROQ, ANTHROPIC, OLLAMA, TERMUX, CUSTOM)

    fun find(id: String?): AgentPreset? = all.firstOrNull { it.id == id }
}
