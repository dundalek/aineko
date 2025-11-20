{
  description = "Aineko - CLI tool for managing coding agent seances";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        aineko = pkgs.stdenv.mkDerivation {
          pname = "aineko";
          version = "0.1.0";

          src = ./.;

          nativeBuildInputs = [ pkgs.makeWrapper ];

          buildInputs = [
            pkgs.babashka
            pkgs.zellij
            pkgs.fzf
          ];

          installPhase = ''
            mkdir -p $out/bin
            mkdir -p $out/share/aineko

            # Copy source files
            cp -r src $out/share/aineko/
            cp -r deps.edn bb.edn $out/share/aineko/

            # Create wrapper script
            cat > $out/bin/aineko <<EOF
            #!/usr/bin/env bash
            exec ${pkgs.babashka}/bin/bb -Sdeps '{:paths ["$out/share/aineko/src"]}' -m aineko.main "\$@"
            EOF

            chmod +x $out/bin/aineko

            # Wrap with runtime dependencies in PATH
            wrapProgram $out/bin/aineko \
              --prefix PATH : ${
                pkgs.lib.makeBinPath [
                  pkgs.babashka
                  pkgs.zellij
                  pkgs.fzf
                ]
              }
          '';

          meta = with pkgs.lib; {
            description = "CLI tool for managing multiple Claude Code agent sessions";
            homepage = "https://github.com/dundalek/aineko";
            license = licenses.mit;
            maintainers = [ ];
            platforms = platforms.unix;
          };
        };
      in
      {
        packages.default = aineko;
        packages.aineko = aineko;

        apps.default = {
          type = "app";
          program = "${aineko}/bin/aineko";
        };

        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.babashka
            pkgs.zellij
            pkgs.fzf
            pkgs.watchexec
            pkgs.libnotify # for newer notify-send that supports --action
          ];
        };
      }
    );
}
