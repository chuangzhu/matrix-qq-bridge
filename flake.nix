{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-22.05";
    flake-utils.url = "github:numtide/flake-utils";
    # gradle2nix.url = "github:Scoder12/gradle2nix";
    gradle2nix = {
      url = "github:Mic92/gradle2nix/fix-compat";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "flake-utils";
    };
  };

  outputs = { self, nixpkgs, flake-utils, gradle2nix }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system}; in
      {
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
          '';
          nativeBuildInputs = with pkgs; [
            openjdk
            gradle
            (pkgs.writeScriptBin "dendrite" ''
              mkdir -p dendrite
              [[ -f dendrite/matrix_key.pem ]] || ${pkgs.dendrite}/bin/generate-keys -private-key dendrite/matrix_key.pem
              ${pkgs.dendrite}/bin/dendrite-monolith-server -config dendrite.yaml
            '')
          ];
        };
      });
}
