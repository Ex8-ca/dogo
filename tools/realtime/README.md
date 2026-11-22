# Realtime voice token service

Dogo's voice mode talks to the OpenAI Realtime API directly from the phone.
To keep your OpenAI key off the phone, run this small service on a computer
you trust. Phones ask it for a client secret (`ek_...`) that expires after ten
minutes and only works for the Realtime API.

- `token_server.py` uses only the Python standard library.
- It reads `OPENAI_API_KEY` and `API_SERVER_KEY` from `~/.hermes/.env`. Set
  `DOGO_ENV_FILE` to read a different file.
- Phones authenticate with `API_SERVER_KEY`, the same key Hermes uses, so a
  Dogo already set up for Hermes needs nothing new.
- It listens on `127.0.0.1:8797` only, and allows at most 10 sessions a minute.

## Install

```sh
mkdir -p ~/dogo-realtime ~/.config/systemd/user
cp token_server.py ~/dogo-realtime/
cp dogo-realtime-token.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now dogo-realtime-token.service
```

Then expose it over HTTPS on the same host and port as Hermes. With Tailscale:

```sh
tailscale funnel --bg --https=10000 --set-path /dogo/realtime http://127.0.0.1:8797
```

Use `tailscale funnel`, not `tailscale serve`, on a port that is already
funnelled. `serve` turns Funnel off for the whole port, which also cuts off
Hermes.

In Dogo, go to Options > Voice and tap **Use my Hermes server**, then **Test
voice key**.

## API

`POST /token` with header `Authorization: Bearer <API_SERVER_KEY>` and body
`{"model": "gpt-realtime-2.1"}`. The model is optional and limited to the
realtime models listed by `GET /health`.

It returns `{"value": "ek_...", "expires_at": <unix time>, "model": "..."}`.
Errors are `401` for a wrong key, `429` when rate limited, and `502` when
OpenAI refused or couldn't be reached.
