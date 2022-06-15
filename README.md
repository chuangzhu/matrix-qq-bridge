# matrix-qq-bridge

Work in progress. A Matrix-QQ [puppeting bridge](https://matrix.org/docs/guides/types-of-bridging#simple-puppeted-bridge) based on [Trixnity](https://gitlab.com/benkuly/trixnity) and [Mirai](https://github.com/mamoe/mirai).

## Building

```shellsession
$ gradle installDist
```

JARs and a wrapper can be found in `app/build/install/app/`.

### Using Nix flakes

```shellsession
$ nix build
```

## Features and roadmap

* QQ → Matrix
  * [ ] Message content
    * [x] Text
    * [ ] Files (partial implementation in Mirai, group only)
    * [x] Picture
    * [x] Sticker
    * [x] Emoji
    * [x] Message reply
    * [x] Mention
    * [ ] Nudge
    * [ ] Withdraw message
    * [ ] Miniapp message
    * [ ] Audio
    * Location (not yet supported by Mirai)
    * Video (not yet supported by Mirai)
  * [x] Group message
  * [ ] Friend message
  * [ ] Stranger message
  * [ ] OtherClient message
  * [ ] Presence
  * [ ] Group permissions
  * [ ] Membership actions (invite/kick/join/leave)
  * [x] Initial chat metadata
    * [x] Group name
    * [x] Group avatar
    * [x] User name
    * [x] User avatar
  * [ ] Chat metadata changes
    * [ ] Group name
    * [ ] Group avatar
    * [ ] Group member name
    * [ ] Group member avatar
    * [ ] Friend nick
    * [ ] Friend avatar
    * [ ] Stranger nick
    * [ ] Stranger avatar
  * [ ] Double puppeting
* Matrix → QQ
  * [ ] Message content
    * [x] Text
    * [ ] Files
    * [x] Picture
    * [x] Sticker
    * [x] Emoji
    * [ ] Message reply
    * [x] Mention
    * [ ] Redact message (within 2 mins)
    * [ ] Location
    * [ ] Emote
    * [ ] Audio
    * [ ] Video (to a file)
  * [x] Room message
  * [ ] Direct message
  * [ ] Relay userbot and plumbed room
