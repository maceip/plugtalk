//! Pixel buffer types for RGBA/BGRA frame snapshots.

use crate::ipc::NodeRect;

/// A rendered frame.
pub struct PaintBuffer<'a> {
    /// The BGRA or RGBA pixel data.
    pub pixels: &'a [u8],
    /// The width of the frame in pixels.
    pub width: i32,
    /// The height of the frame in pixels.
    pub height: i32,
    /// The number of bytes per row.
    pub stride: usize,
    /// The rectangles that have to be repainted.
    pub dirty_rects: &'a [NodeRect],
}

/// The pixel format of a frame buffer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PixelFormat {
    /// Blue, green, red, alpha.
    Bgra8888,
    /// Red, green, blue, alpha (`ARGB_8888` byte order on Android).
    Rgba8888,
}

impl PixelFormat {
    /// `true` when red and blue channels must be swapped to reach `target`.
    pub fn needs_swizzle(&self, target: PixelFormat) -> bool {
        *self != target
    }
}

/// Receives rendered frames.
pub trait FrameSink: Send + Sync {
    /// Present a frame.
    fn present(&self, buffer: PaintBuffer<'_>);
    /// The size of the surface in pixels.
    fn viewport(&self) -> (i32, i32);
    /// The pixel format of the surface.
    fn pixel_format(&self) -> PixelFormat {
        PixelFormat::Rgba8888
    }
}
