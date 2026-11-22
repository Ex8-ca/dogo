# Hermes tools-off route for Dogo

Dogo reads notifications, other apps' screens and web pages. That text is untrusted, and a prompt
cannot switch off a tool-equipped agent. The only safe way to let Hermes handle that text is a
route where the server itself has no tools. This describes how it is set up and what is checked.

Checked on 2026-11-22 against Hermes 0.23.3 (source read, then tested on a live gateway).

## Why a separate profile

The Hermes API server picks the agent's toolsets from the server's own `platform_toolsets.api_server`
setting. No code reads a tool list from the request, so the phone cannot ask for tools and cannot
remove them. A profile is its own config, reached at `/p/<profile>/v1/...` on the same listener and
port, with its own API key. Dogo points notification work at that profile.

## Setup (on the Hermes host)

    hermes profile create dogosafe --clone-from default --no-alias \
      --description "Dogo: reads untrusted phone text. No tools by design."

Then edit `~/.hermes/profiles/dogosafe/config.yaml`:

    platform_toolsets:
      api_server: []            # an explicitly saved empty list means zero toolsets
      ...
    agent:
      disabled_toolsets: [homeassistant, chrome_profiles]   # plugin toolsets that stay on otherwise
    auxiliary:
      background_review:
        enabled: false          # stops the automatic memory/skill review fork
    memory:
      memory_enabled: false
      user_profile_enabled: false
      provider: ""              # a clone inherits the shared memory provider; cut it
    curator:
      enabled: false

Give it its own key: add `API_SERVER_KEY=<a new random value>` to
`~/.hermes/profiles/dogosafe/.env`. Do not reuse the default profile's key. In Dogo, enter the new
key and the address `https://<host>:<port>/p/dogosafe/v1`.

Why each line: with only `api_server: []`, two plugin toolsets (`homeassistant`, `chrome_profiles`)
were still enabled, because plugin toolsets are on by default unless listed. They had to be
disabled by name. The memory and review settings stop notification text from being written into
memory or turned into a skill.

## What was tested

- `/p/dogosafe/v1/toolsets` lists 28 toolsets with none enabled (0 tools). The default profile
  lists 29 tools enabled.
- Asked to read a file that only a tool could read: the default profile returned its contents;
  `dogosafe` answered "NO TOOLS".
- A hostile notification asking it to save a PIN to memory, create a skill and run a command, sent
  three times: nothing was written to the profile's memories or skills, nothing to the default
  profile, no skill created. (The model also called it a prompt injection, but that is luck, not
  the guarantee. The guarantee is that there are no tools.)
- A wrong prefix (`/p/nosuchprofile/`) returns 404, so a mistyped address fails instead of
  falling back to the default profile.

## What Dogo checks, and what it cannot

Before every use (cached for five minutes) Dogo asks the route for `/v1/toolsets` and refuses it
unless the answer lists toolsets and none is enabled. It also refuses a route that has the same
address or key as the normal Hermes route, or that is on a different host.

Dogo cannot see the memory, review and curator settings from the phone. They are the host
owner's responsibility, and the steps above set them. Conversations sent to this profile are still
kept in its session history on the server.
