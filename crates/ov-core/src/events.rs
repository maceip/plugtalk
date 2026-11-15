//! Pure-Rust Android input event translation helpers.

/// Event modifier bitflags.
pub mod modifiers {
    /// No modifier is pressed.
    pub const NONE: u32 = 0;
    /// Shift key.
    pub const SHIFT: u32 = 1 << 0;
    /// Control key.
    pub const CTRL: u32 = 1 << 1;
    /// Alt key.
    pub const ALT: u32 = 1 << 2;
    /// Meta / Command key.
    pub const META: u32 = 1 << 3;
}

/// Android `KeyEvent` key codes translated by Trap.
pub mod android_key {
    pub const BACK: i32 = 4;
    pub const DPAD_UP: i32 = 19;
    pub const DPAD_DOWN: i32 = 20;
    pub const DPAD_LEFT: i32 = 21;
    pub const DPAD_RIGHT: i32 = 22;
    pub const TAB: i32 = 61;
    pub const SPACE: i32 = 62;
    pub const ENTER: i32 = 66;
    pub const DEL: i32 = 67;
    pub const PAGE_UP: i32 = 92;
    pub const PAGE_DOWN: i32 = 93;
    pub const ESCAPE: i32 = 111;
    pub const FORWARD_DEL: i32 = 112;
    pub const MOVE_HOME: i32 = 122;
    pub const MOVE_END: i32 = 123;
}

/// Windows virtual key codes expected by Chromium/Blink input protocols.
pub mod vk {
    pub const BACK: i32 = 0x08;
    pub const TAB: i32 = 0x09;
    pub const RETURN: i32 = 0x0D;
    pub const SHIFT: i32 = 0x10;
    pub const CONTROL: i32 = 0x11;
    pub const MENU: i32 = 0x12;
    pub const ESCAPE: i32 = 0x1B;
    pub const SPACE: i32 = 0x20;
    pub const PRIOR: i32 = 0x21;
    pub const NEXT: i32 = 0x22;
    pub const END: i32 = 0x23;
    pub const HOME: i32 = 0x24;
    pub const LEFT: i32 = 0x25;
    pub const UP: i32 = 0x26;
    pub const RIGHT: i32 = 0x27;
    pub const DOWN: i32 = 0x28;
    pub const INSERT: i32 = 0x2D;
    pub const DELETE: i32 = 0x2E;
    pub const F1: i32 = 0x70;
}

/// Translate an Android key code into a Chromium/Windows virtual key code.
pub fn android_key_to_vk(code: i32) -> i32 {
    match code {
        android_key::BACK => vk::BACK,
        android_key::TAB => vk::TAB,
        android_key::ENTER => vk::RETURN,
        android_key::SPACE => vk::SPACE,
        android_key::DEL => vk::BACK,
        android_key::FORWARD_DEL => vk::DELETE,
        android_key::ESCAPE => vk::ESCAPE,
        android_key::PAGE_UP => vk::PRIOR,
        android_key::PAGE_DOWN => vk::NEXT,
        android_key::MOVE_HOME => vk::HOME,
        android_key::MOVE_END => vk::END,
        android_key::DPAD_LEFT => vk::LEFT,
        android_key::DPAD_UP => vk::UP,
        android_key::DPAD_RIGHT => vk::RIGHT,
        android_key::DPAD_DOWN => vk::DOWN,
        59 | 60 => vk::SHIFT,
        113 | 114 => vk::CONTROL,
        57 | 58 => vk::MENU,
        117 | 118 => 0x5B,
        code if (131..=140).contains(&code) => vk::F1 + (code - 131),
        code if (0x20..0x7F).contains(&code) => code,
        _ => 0,
    }
}

/// Pure-Rust pointer coordinates and modifier mask.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PointerPosition {
    pub x: i32,
    pub y: i32,
    pub modifiers: u32,
}

/// Convert floating-point Android pointer coordinates to integer CSS/viewport pixels.
pub fn mouse_event(x: f32, y: f32, modifiers: u32) -> PointerPosition {
    PointerPosition {
        x: x as i32,
        y: y as i32,
        modifiers,
    }
}

/// Action of an Android `MotionEvent`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TouchAction {
    Down,
    Move,
    Up,
    Cancel,
}

impl TouchAction {
    /// Parse the masked action byte of an Android `MotionEvent`.
    pub fn from_android(action: i32) -> Option<TouchAction> {
        match action & 0xFF {
            0 => Some(TouchAction::Down),
            1 => Some(TouchAction::Up),
            2 => Some(TouchAction::Move),
            3 => Some(TouchAction::Cancel),
            _ => None,
        }
    }
}

/// Kind of keyboard event.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyAction {
    Down,
    Up,
    Char,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_arrow_keys_are_translated() {
        assert_eq!(android_key_to_vk(android_key::DPAD_LEFT), vk::LEFT);
        assert_eq!(android_key_to_vk(android_key::DPAD_DOWN), vk::DOWN);
    }

    #[test]
    fn the_function_keys_are_translated() {
        assert_eq!(android_key_to_vk(131), vk::F1);
        assert_eq!(android_key_to_vk(140), vk::F1 + 9);
    }

    #[test]
    fn the_touch_actions_are_parsed() {
        assert_eq!(TouchAction::from_android(0), Some(TouchAction::Down));
        assert_eq!(TouchAction::from_android(1), Some(TouchAction::Up));
        assert_eq!(TouchAction::from_android(2), Some(TouchAction::Move));
        assert_eq!(TouchAction::from_android(5), None);
    }

    #[test]
    fn the_mouse_position_is_converted_to_integers() {
        let event = mouse_event(10.7, 20.2, modifiers::NONE);
        assert_eq!(event.x, 10);
        assert_eq!(event.y, 20);
    }
}
