{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system}; in
      {
        devShell = pkgs.mkShell {
          nativeBuildInputs = with pkgs; [
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
