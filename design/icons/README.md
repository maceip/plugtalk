# Trap Icon Pipeline (`1024x1024` `nodpi`)

Trap's launcher, adaptive, monochrome, in-app, and store icons are synced from
the two canonical `1024x1024` 8-bit RGBA PNG sources in `design/icons/src/`
with **zero resizing**:

- `src/trap-icon.png` — full-color `1024x1024` `TR/AP` squircle icon
- `src/trap-icon-monochrome.png` — monochrome `1024x1024` `TR/AP` squircle icon

```sh
python3 design/icons/trap_icon.py           # write the 1024x1024 nodpi assets
python3 design/icons/trap_icon.py --check   # verify zero drift & 1024x1024 dimensions
```

`trap_icon.py` uses only the Python 3 standard library (`struct`, `zlib`), never
calls `Image.resize`, removes legacy per-density `mipmap-{mdpi..xxxhdpi}` PNGs,
and verifies in CI (`--check`) that every icon PNG remains `1024x1024`.

## Generated `1024x1024` Assets

| Asset | Path | Description |
| --- | --- | --- |
| Launcher icon | `android/app/src/main/res/mipmap-nodpi/ic_launcher.png` | Byte-for-byte `1024x1024` copy of `src/trap-icon.png` |
| Round launcher icon | `android/app/src/main/res/mipmap-nodpi/ic_launcher_round.png` | Byte-for-byte `1024x1024` copy of `src/trap-icon.png` |
| Adaptive foreground | `android/app/src/main/res/mipmap-nodpi/ic_launcher_foreground.png` | Byte-for-byte `1024x1024` copy of `src/trap-icon.png`, wrapped with `<inset android:inset="18%" />` in `mipmap-anydpi-v26` |
| Adaptive monochrome | `android/app/src/main/res/mipmap-nodpi/ic_launcher_monochrome.png` | Unresized `1024x1024` luminance-to-alpha (`RGB=(255,255,255), A = lum * orig_A / 255`) mask from `src/trap-icon-monochrome.png` for Android 13+ Material You themed icons (`<inset android:inset="16.67%" />`) |
| In-app mark | `android/app/src/main/res/drawable-nodpi/ov_trap_mark.png` | Byte-for-byte `1024x1024` copy of `src/trap-icon.png` |
| Store & README icons | `design/icons/out/play-store-1024.png`, `design/icons/out/play-store-512.png` | Byte-for-byte unresized `1024x1024` copies of `src/trap-icon.png` (`play-store-512.png` path preserved for root `README.md`) |

## Adaptive Icon Safe Zone (`mipmap-anydpi-v26`)

Because `ic_launcher_foreground.png` and `ic_launcher_monochrome.png` are kept
as full-bleed `1024x1024` `mipmap-nodpi` squircles without resampling onto
padded per-density canvases, `res/mipmap-anydpi-v26/ic_launcher.xml` and
`ic_launcher_round.xml` wrap both layers in Android `<inset>` drawables over
`@color/ic_launcher_background` (`#FF181818`, sampled from the outer charcoal
squircle rim) so circular and squircle `72dp/108dp` launcher masks never clip
the `TR/AP` lettering.

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
`--check` compares the files of the tree with a fresh rendering.

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
