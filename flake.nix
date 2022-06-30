{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-22.05";
    flake-utils.url = "github:numtide/flake-utils";
    gradle2nix = {
      url = "github:lorenzleutgeb/gradle2nix";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "flake-utils";
    };
  };

  outputs = { self, nixpkgs, flake-utils, gradle2nix }:
    {
      nixosModules.matrix-qq-bridge = { config, lib, pkgs, ... }:
        let
          cfg = config.services.matrix-qq-bridge;
          app = "${self.defaultPackage.${pkgs.stdenv.system}}/bin/app";
          dataDir = "/var/lib/matrix-qq-bridge";
          # All JSON files are valid YAML
          settingsFile = pkgs.writeText "matrix-qq-bridge.yaml" (builtins.toJSON cfg.settings);
          registrationFile = "${dataDir}/qq-registration.yaml";
        in
        {
          # Interface
          options.services.matrix-qq-bridge = with lib; rec {
            enable = mkEnableOption "a Matrix-QQ puppeting bridge";
            settings = mkOption rec {
              type = types.attrs;
              apply = recursiveUpdate default;
              default = {
                homeserver = { };
                appservice = rec {
                  address = "http://localhost:${toString port}";
                  hostname = "0.0.0.0";
                  port = 8245;
                  database = "sqlite:${dataDir}/matrix-qq-bridge.db";
                  id = "qq";
                  bot_username = "qqbridge";
                  username_prefix = "_qq_";
                  alias_prefix = "_qq_";
                };
                bridge = {
                  permissions = { };
                };
              };
            };
            serviceDependencies = mkOption {
              type = types.listOf types.str;
              default = [ ];
            };
          };

          # Implementation
          config = lib.mkIf cfg.enable {
            systemd.services.matrix-qq-bridge = {
              description = "Matrix-QQ puppeting bridge";
              wantedBy = [ "multi-user.target" ];
              wants = [ "network-online.target" ] ++ cfg.serviceDependencies;
              after = [ "network-online.target" ] ++ cfg.serviceDependencies;
              preStart = ''
                if [ ! -f '${registrationFile}' ]; then
                  ${app} '${settingsFile}' > '${registrationFile}'
                fi
              '';
              serviceConfig = {
                ExecStart = "${app} '${settingsFile}' '${registrationFile}'";
                Restart = "always";
                ProtectSystem = "strict";
                ProtectHome = true;
                ProtectKernelTunables = true;
                ProtectKernelModules = true;
                ProtectControlGroups = true;
                DynamicUser = true;
                PrivateTmp = true;
                StateDirectory = baseNameOf dataDir;
                WorkingDirectory = dataDir;
                UMask = 0077;
              };
            };
          };
        };
    } //

    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system}; in
      {
        defaultPackage = pkgs.callPackage ./gradle-env.nix { } {
          envSpec = ./gradle-env.json;
          src = ./.;
          gradleFlags = [ "installDist" ];
          installPhase = ''
            mkdir -p $out
            cp -r app/build/install/app/. $out
          '';
        };

        # nix develop .#gradle2nix
        devShells.gradle2nix = pkgs.mkShell {
          shellHook = ''
            PS1="\n\[\033[1;33m\][gradle2nix:\w]\$\[\033[0m\] "
          '';
          nativeBuildInputs = with pkgs; [
            openjdk11
            gradle2nix.defaultPackage.${system}
          ];
        };

        # nix develop
        devShells.default = pkgs.mkShell {
          shellHook = ''
            PS1="\n\[\033[1;33m\][matrix-qq-bridge:\w]\$\[\033[0m\] "
            alias grep='grep --color=auto --exclude-dir=.git --exclude-dir=dendrite --exclude-dir=tar --exclude-dir=build'
          '';
          nativeBuildInputs = with pkgs; [
            gradle_6
            (pkgs.writeScriptBin "dendrite" ''
              mkdir -p dendrite
              [[ -f dendrite/matrix_key.pem ]] || ${pkgs.dendrite}/bin/generate-keys -private-key dendrite/matrix_key.pem
              ${pkgs.dendrite}/bin/dendrite-monolith-server -config dendrite.yaml
            '')
            sqlite
          ];
        };
      });
}
