# The Trap icon lockups

The launcher icon of the application is generated from the canonical Trap icon
artwork in `src/trap-icon.webp` (paired with its lossless RGBA decode
`src/trap-icon.png` for cross-distro bit-exactness), resized cleanly without
cropping, masking, or recolouring so every icon surface stays faithful to the
source artwork.

    python3 design/icons/trap_icon.py           # write the files
    python3 design/icons/trap_icon.py --check   # fail when they drifted

The script resizes the canonical artwork into `android/app/src/main/res` and
`design/icons/out`.  `--check` compares the files in the tree against a fresh
rendering; continuous integration runs it on every push and fails the build
when any icon file drifted.

The script needs Pillow, nothing else: no browser, no network.

## The lockups

| lockup | file | use |
| --- | --- | --- |
| hero icon | `mipmap-*/ic_launcher.png` | the unmodified V3 cutout resized for `mdpi` (`48px`) through `xxxhdpi` (`192px`) |
| round icon | `mipmap-*/ic_launcher_round.png` | the unmodified V3 cutout resized for launchers requesting `roundIcon` |
| adaptive foreground | `mipmap-*/ic_launcher_foreground.png` | the unmodified V3 cutout fitted inside the 72dp safe zone across all 5 densities (`108px`..`432px`) |
| adaptive background | `mipmap-*/ic_launcher_background.png` | the `#0D080B` obsidian-plum shutter field across all 5 densities (`108px`..`432px`) |
| adaptive monochrome | `mipmap-*/ic_launcher_monochrome.png` | the shutter-striped `TR/AP` glyph mask for Android 13+ themed icons (`108px`..`432px` + `out/ic_launcher_monochrome_512.png`) |
| the mark | `drawable-nodpi/ov_trap_mark.png` | the unmodified `512px` V3 mark used on the start and crash screens |
| store icon | `design/icons/out/play-store-1024.png` | the full `1024px` and `512px` (`play-store-512.png`) exports |
| light lockup | `design/icons/out/trap-lockup.svg` | the unmodified resized icon next to `#180D12` wordmark ink (`trap-lockup.png` / `.svg`) |
| dark lockup | `design/icons/out/trap-lockup-dark.svg` | the unmodified resized icon on `#0D080B` with `#F5EEF4` wordmark ink (`trap-lockup-dark.png` / `.svg`) |

## The safe zone of the adaptive icon

Android launchers mask the 108dp adaptive canvas down to the central 72dp
viewport (often a circle).  `trap_icon.py` measures the exact radial extent of
all non-transparent pixels in `src/trap-icon.png` and resizes the unmodified
artwork so every pixel of the lettering sits cleanly inside the 72dp safe zone
without clipping.

The wordmark is set in the subset of DejaVu Sans Bold that is part of the
repository (`fonts/Trap-Bold.ttf`); it carries the glyphs of the wordmark
only, so that the lockup is the same on every machine and the check cannot
drift.  The licence of the font is next to it
(`fonts/LICENSE-DejaVu.txt`).  The vector lockup embeds the font, so it
renders the same everywhere; outline the text before it is used for anything
that has to look exactly the same everywhere.

## The neon glyphs of the chrome

The interface of the browser is painted with the **TRAP Neon Graffiti
Browser Board**, the design board of the application.  The board carries the
icon system, the components of the chrome and the brand palette.

    python3 design/icons/neon/slice_neon.py           # write the files
    python3 design/icons/neon/slice_neon.py --check   # fail when they drifted

The script cuts every glyph out of the board (`neon/trap-board.jpg`) and
writes it into `android/app/src/main/res/drawable-nodpi/ic_neon_*.png`.  The
alpha of a pixel is how far it is from the near black of the board, so the
glow of the neon fades out and the dark outline of a glyph becomes the glow
itself; the lavender of the board, the labels of the icons and the frames of
the panels are dropped.  The rendering is byte for byte reproducible and
`--check` compares the files of the tree with a fresh rendering, exactly like
the pipeline of the launcher icon does.

The script needs Pillow and NumPy, nothing else.

| asset | what it is |
| --- | --- |
| `ic_neon_menu`, `ic_neon_dots` | the menu of the browser, in the top bar and in the navigation row |
| `ic_neon_trap_logo`, `ic_neon_onion_twins` | the wordmark of the brand and the two onions of the top bar |
| `ic_neon_back`, `ic_neon_forward`, `ic_neon_home`, `ic_neon_new_tab`, `ic_neon_reload` | the row of the navigation |
| `ic_neon_shield`, `ic_neon_cancel` | the shield of the leak protection and the button that clears the address |
| `ic_neon_onion`, `ic_neon_key` | the tiles of the dock: the routing over Tor and the keyring |
| `ic_neon_zoom_in`, `ic_neon_zoom_out` | the zoom of the menu of the browser |
| `ic_neon_new_partial_tab`, `ic_neon_autofill`, `ic_neon_sign_pgp`, `ic_neon_decrypt_pgp`, `ic_neon_mic`, `ic_neon_microphone`, `ic_neon_monero`, `ic_neon_usd`, `ic_neon_wallet`, `ic_neon_update` | the rest of the icon system of the board |
