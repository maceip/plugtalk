//! Viewport dimensions and pixel-row swizzle helpers.

use std::sync::Mutex;

use ov_cef::{FrameSink, PaintBuffer, PixelFormat};

/// Tracks viewport size and pixel format for off-screen frame tests.
pub struct SurfaceRenderer {
    size: Mutex<(i32, i32)>,
    format: PixelFormat,
}

impl SurfaceRenderer {
    /// Create a renderer with `format`.
    pub fn new(format: PixelFormat) -> Self {
        Self {
            size: Mutex::new((0, 0)),
            format,
        }
    }

    /// Resize the tracked viewport.
    pub fn resize(&self, width: i32, height: i32) {
        if let Ok(mut guard) = self.size.lock() {
            *guard = (width, height);
        }
    }

    /// Current viewport size in pixels.
    pub fn size(&self) -> (i32, i32) {
        self.size.lock().map(|g| *g).unwrap_or((0, 0))
    }
}

impl FrameSink for SurfaceRenderer {
    fn present(&self, _buffer: PaintBuffer<'_>) {}

    fn viewport(&self) -> (i32, i32) {
        self.size()
    }

    fn pixel_format(&self) -> PixelFormat {
        self.format
    }
}

/// Copy one row of RGBA/BGRA pixels, optionally swapping red and blue channels.
pub fn copy_row(dst: &mut [u8], src: &[u8], swizzle: bool) {
    for (target, pixel) in dst.chunks_mut(4).zip(src.chunks(4)) {
        if pixel.len() < 4 || target.len() < 4 {
            break;
        }
        if swizzle {
            target[0] = pixel[2];
            target[1] = pixel[1];
            target[2] = pixel[0];
        } else {
            target[0] = pixel[0];
            target[1] = pixel[1];
            target[2] = pixel[2];
        }
        target[3] = pixel[3];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rows_are_copied_verbatim_when_the_formats_match() {
        let src = [1u8, 2, 3, 4, 5, 6, 7, 8];
        let mut dst = [0u8; 8];
        copy_row(&mut dst, &src, false);
        assert_eq!(dst, src);
    }

    #[test]
    fn rows_are_swizzled_when_the_formats_differ() {
        let src = [1u8, 2, 3, 4];
        let mut dst = [0u8; 4];
        copy_row(&mut dst, &src, true);
        assert_eq!(dst, [3, 2, 1, 4]);
    }
}
