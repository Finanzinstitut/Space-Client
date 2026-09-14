//! Reads the game's own server list out of `servers.dat`.
//!
//! The launcher needs this because the per-server mod profiles are keyed on the
//! servers you actually have, and the only place that list exists is the file
//! Minecraft keeps. Asking you to type the addresses a second time would put
//! two lists out of step the first time you added a server in game.
//!
//! `servers.dat` is plain uncompressed NBT - Mojang's own binary format, big
//! endian throughout. No crate is pulled in for it: what is needed here is one
//! read-only walk over a format that has not changed in a decade, and a new
//! dependency in a tree this size costs more than the sixty lines below.
//!
//! Nothing here trusts the file. Every read is bounds checked, list lengths are
//! checked against what is actually left in the buffer before anything is
//! allocated, and the recursion is capped - a truncated or hand-edited
//! `servers.dat` must come back as an empty list with a reason, never as a
//! panic that takes the launcher down.

use serde::Serialize;
use std::path::Path;

#[derive(Debug, Clone, Serialize)]
pub struct ServerEntry {
    pub name: String,
    pub address: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ServerList {
    pub servers: Vec<ServerEntry>,
    /// Why the list is empty, when it is. Empty string when all is well.
    pub note: String,
}

impl ServerList {
    fn empty(note: &str) -> Self {
        ServerList { servers: Vec::new(), note: note.to_string() }
    }
}

const TAG_END: u8 = 0;
const TAG_BYTE: u8 = 1;
const TAG_SHORT: u8 = 2;
const TAG_INT: u8 = 3;
const TAG_LONG: u8 = 4;
const TAG_FLOAT: u8 = 5;
const TAG_DOUBLE: u8 = 6;
const TAG_BYTE_ARRAY: u8 = 7;
const TAG_STRING: u8 = 8;
const TAG_LIST: u8 = 9;
const TAG_COMPOUND: u8 = 10;
const TAG_INT_ARRAY: u8 = 11;
const TAG_LONG_ARRAY: u8 = 12;

/// Deep enough for any real save, shallow enough that a hand made file cannot
/// blow the stack.
const MAX_DEPTH: u32 = 64;

struct Cursor<'a> {
    data: &'a [u8],
    at: usize,
}

impl<'a> Cursor<'a> {
    fn left(&self) -> usize {
        self.data.len().saturating_sub(self.at)
    }

    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        if self.left() < n {
            return None;
        }
        let slice = &self.data[self.at..self.at + n];
        self.at += n;
        Some(slice)
    }

    fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }

    fn u16(&mut self) -> Option<u16> {
        let b = self.take(2)?;
        Some(u16::from_be_bytes([b[0], b[1]]))
    }

    fn i32(&mut self) -> Option<i32> {
        let b = self.take(4)?;
        Some(i32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn string(&mut self) -> Option<String> {
        let len = self.u16()? as usize;
        let bytes = self.take(len)?;
        // Lossy on purpose: NBT stores modified UTF-8, and a server name with
        // an odd byte in it should still list rather than drop the whole file.
        Some(String::from_utf8_lossy(bytes).into_owned())
    }

    /// Guards a length field before it is used to allocate or to loop.
    ///
    /// Every element costs at least one byte, so a count larger than what is
    /// left cannot be honest - and without this a corrupt four byte length
    /// would have us spinning over two billion nonexistent entries.
    fn plausible(&self, count: i32, each: usize) -> Option<usize> {
        if count < 0 {
            return Some(0);
        }
        let count = count as usize;
        if count.saturating_mul(each.max(1)) > self.left() {
            return None;
        }
        Some(count)
    }
}

enum Value {
    Str(String),
    List(Vec<Value>),
    Compound(Vec<(String, Value)>),
    /// Read past and discarded: nothing in a server list needs numbers.
    Other,
}

fn read_value(c: &mut Cursor, tag: u8, depth: u32) -> Option<Value> {
    if depth > MAX_DEPTH {
        return None;
    }
    match tag {
        TAG_END => Some(Value::Other),
        TAG_BYTE => {
            c.take(1)?;
            Some(Value::Other)
        }
        TAG_SHORT => {
            c.take(2)?;
            Some(Value::Other)
        }
        TAG_INT | TAG_FLOAT => {
            c.take(4)?;
            Some(Value::Other)
        }
        TAG_LONG | TAG_DOUBLE => {
            c.take(8)?;
            Some(Value::Other)
        }
        TAG_BYTE_ARRAY => {
            let n = c.i32()?;
            let n = c.plausible(n, 1)?;
            c.take(n)?;
            Some(Value::Other)
        }
        TAG_STRING => Some(Value::Str(c.string()?)),
        TAG_LIST => {
            let elem = c.u8()?;
            let n = c.i32()?;
            let n = c.plausible(n, 1)?;
            let mut out = Vec::new();
            for _ in 0..n {
                out.push(read_value(c, elem, depth + 1)?);
            }
            Some(Value::List(out))
        }
        TAG_COMPOUND => {
            let mut out = Vec::new();
            loop {
                let t = c.u8()?;
                if t == TAG_END {
                    break;
                }
                let name = c.string()?;
                let value = read_value(c, t, depth + 1)?;
                out.push((name, value));
            }
            Some(Value::Compound(out))
        }
        TAG_INT_ARRAY => {
            let n = c.i32()?;
            let n = c.plausible(n, 4)?;
            c.take(n * 4)?;
            Some(Value::Other)
        }
        TAG_LONG_ARRAY => {
            let n = c.i32()?;
            let n = c.plausible(n, 8)?;
            c.take(n * 8)?;
            Some(Value::Other)
        }
        _ => None,
    }
}

fn field<'v>(compound: &'v [(String, Value)], name: &str) -> Option<&'v Value> {
    compound.iter().find(|(key, _)| key == name).map(|(_, value)| value)
}

fn text(compound: &[(String, Value)], name: &str) -> Option<String> {
    match field(compound, name) {
        Some(Value::Str(s)) => Some(s.clone()),
        _ => None,
    }
}

pub fn parse(data: &[u8]) -> ServerList {
    if data.len() >= 2 && data[0] == 0x1f && data[1] == 0x8b {
        // Vanilla writes this one uncompressed. A gzip header means some other
        // tool rewrote it, and saying so beats reporting "no servers".
        return ServerList::empty("servers.dat is gzip compressed, which this reader does not handle");
    }

    let mut c = Cursor { data, at: 0 };
    match c.u8() {
        Some(TAG_COMPOUND) => {}
        Some(_) => return ServerList::empty("servers.dat does not start with a compound tag"),
        None => return ServerList::empty("servers.dat is empty"),
    }
    if c.string().is_none() {
        return ServerList::empty("servers.dat ends inside its root name");
    }

    let root = match read_value(&mut c, TAG_COMPOUND, 0) {
        Some(Value::Compound(entries)) => entries,
        _ => return ServerList::empty("servers.dat could not be read to the end"),
    };

    let list = match field(&root, "servers") {
        Some(Value::List(items)) => items,
        _ => return ServerList::empty("servers.dat carries no server list"),
    };

    let mut out = Vec::new();
    for item in list {
        let Value::Compound(entry) = item else { continue };
        let address = match text(entry, "ip") {
            Some(ip) if !ip.trim().is_empty() => ip,
            _ => continue,
        };
        let name = text(entry, "name").unwrap_or_default();
        let name = if name.trim().is_empty() { address.clone() } else { name };
        out.push(ServerEntry { name, address });
    }

    ServerList { servers: out, note: String::new() }
}

/// Reads the list for one instance. A missing file is not a fault: it simply
/// means nobody has added a server in this instance yet.
pub fn read(game_dir: &Path) -> ServerList {
    let path = game_dir.join("servers.dat");
    match std::fs::read(&path) {
        Ok(data) => parse(&data),
        Err(_) => ServerList::empty("no servers.dat yet - add a server in game once"),
    }
}
