# matrix-qq-bridge

Work in progress. A Matrix-QQ [puppeting bridge](https://matrix.org/docs/guides/types-of-bridging#simple-puppeted-bridge) based on [Trixnity](https://gitlab.com/benkuly/trixnity) and [Mirai](https://github.com/mamoe/mirai).

## Building

```shellsession
$ gradle installDist
```

JARs and a wrapper can be found in `app/build/install/app/`.

## Deployment

A example config for the bridge is at [config-example.yaml](./config-example.yaml).

Generate registration config file for your homeserver:

```shellsession
$ ./app/build/install/app/bin/app config.yaml > qq-registration.yaml
```

Add the registration config file to your homeserver's config:

```yaml
# Synapse
app_service_config_files:
- /path/to/your/qq-registration.yaml

# Dendrite
app_service_api:
  config_files:
  - /path/to/your/qq-registration.yaml
```

Restart the homeserver, then start the bridge:

```shellsession
$ ./app/build/install/app/bin/app config.yaml qq-registration.yaml
```

### Using Nix flakes

This repository is a flake, and it includes a NixOS module.

```nix
{
  inputs.matrix-qq-bridge.url = "github:chuangzhu/matrix-qq-bridge";
  outputs = { self, nixpkgs, matrix-qq-bridge }: {
    nixosConfigurations.your-host-name = nixpkgs.lib.nixosSystem {
      system = "x86_64-linux";
      modules = [
        matrix-qq-bridge.nixosModules.matrix-qq-bridge
        {
          services.matrix-qq-bridge = {
            enable = true;
            settings = { /* Configuration as Nix attribute set */ };
            serviceDependencies = [ "matrix-synapse.service" ];
          };
          services.matrix-synapse = {
            enable = true;
            settings.app_service_config_files = [ "/run/credentials/matrix-synapse.service/matrix-qq-bridge" ];
          };
          systemd.services.matrix-synapse.serviceConfig.LoadCredential = [
            "matrix-qq-bridge:/var/lib/matrix-qq-bridge/qq-registration.yaml"
          ];
        }
      ];
    };
  };
}
```

## Usage

Create a direct chat with `@<appservice.bot_username>:<homeserver.domain>`. Available commands:

- `!login` - Get instruction to login to QQ.
- `!listclient` - List other clients of current QQ account.
- `!cancel` - Cancel an ongoing action

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
    * [x] Nudge
    * [ ] Recall message
    * [ ] Miniapp message
    * [x] Forwarded message
    * [ ] Audio
    * Location (not yet supported by Mirai)
    * Video (not yet supported by Mirai)
  * [x] Group message
  * [ ] Friend message
  * [ ] Stranger message
  * [ ] OtherClient message
  * [ ] Presence
  * [x] Group permissions
  * [ ] Membership actions (invite/kick/join/leave/mute)
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
    * [x] Message reply
    * [x] Mention
    * [ ] Redact message (within 2 mins)
    * [ ] Location
    * [ ] Audio
    * [ ] Video (to a file)
  * [x] Room message
  * [ ] Direct message
  * [ ] Relay userbot and plumbed room
