# Streamer mode — what you have to do

## 1. Register a Twitch application

<https://dev.twitch.tv/console/apps> → **Register Your Application**

| Field | Value |
|---|---|
| Name | anything, e.g. `Space Client` |
| OAuth Redirect URLs | `http://localhost` |
| Category | Application Integration |
| Client Type | **Public** |

Client Type is the one that matters. The device code grant only works for a
public client, and a public client is issued no secret — which is the point.
There is nothing here that could leak from the mod, because the mod never sees
any of it.

Copy the **Client ID**. Ignore the secret button; you do not need one.

## 2. Put the id in the worker

In `wrangler.toml`:

```toml
TWITCH_CLIENT_ID = "your client id here"
```

Then deploy:

```
wrangler deploy
```

The id is not a secret and belongs in the file. If a later change ever needs a
secret, that goes in `wrangler secret put` and never in `wrangler.toml`.

## 3. In game

Right shift → **Streamer** at the bottom right of the panel.

- **Streamer mode** on switches Coordinates and Coords Copy off, and remembers
  which of them had been on so switching it back off restores exactly those.
- **Link Twitch account** shows a short code. Open `twitch.tv/activate`, type
  it, approve. The screen notices by itself within about five seconds.
- **Follower display** turns on the HUD element. Move it with Move HUD like any
  other.

## What it shows

The Twitch mark, the follower count, and the newest follower's name. All three
come from one Twitch call, cached for a minute on the worker — Twitch rate
limits per application, so every linked player draws from the same bucket and
the cache is what keeps that bounded.

## Why Twitch only

Not a shortcut. The other two will not give you what the feature needs:

- **YouTube** publishes a subscriber count, but will not say who subscribed
  unless that person made their subscription public, which almost nobody does.
  A "newest subscriber" line would be blank or misleading nearly always.
- **TikTok** publishes neither. `follower_count` needs `user.info.stats`, which
  needs an app review that takes weeks, and there is no follower list endpoint
  at any tier.

If you want the follower *count* from YouTube next to the Twitch one, that is
buildable — it needs a Data API key and no login at all, since channel counts
are public. Say so and it is a small addition. The newest-follower line stays
Twitch-only whatever we do.

## Tokens

Twitch access tokens last about four hours, which is inside a single evening's
stream, so the worker refreshes them on a 401 rather than treating it as a
broken link. Tokens live in KV keyed by your Minecraft uuid and never reach the
mod.
