# AuthPlus

A lightweight, feature-rich authentication plugin for Bukkit/Spigot/Paper servers. AuthPlus protects your server from unauthorized access by requiring players to register and log in with a password, while offering premium (Mojang) auto-login, multiple database backends, and modern password hashing algorithms out of the box.

## Features

- **Register / Login system** — new players must register with a password; returning players must log in before they can move, chat, or run other commands.
- **Premium auto-login** — legitimate (premium) Minecraft accounts can enable `/premium` to skip password login on future joins, verified against the Mojang API.
- **Multiple database backends** — SQLite, MySQL, MariaDB, PostgreSQL, H2, and MongoDB are all supported out of the box; just pick one in the config.
- **Modern password hashing** — choose from MD5, SHA-256, SHA-512, BCrypt (`$2a$`/`$2y$`), PBKDF2, or Argon2 (id/i/d). Existing passwords keep working even if you change the algorithm later, since the hash type is auto-detected from its stored prefix.
- **Login spawn location** — optionally teleport unauthenticated players to a dedicated "login room" and back to their original position once they're authenticated.
- **Player freezing & invisibility** — unauthenticated players are frozen in place and can optionally be made invisible to others until they log in.
- **Configurable timeout** — players are kicked if they don't register or log in within a configurable time limit.
- **Banned password list** — block common/weak passwords from being used.
- **Fully customizable messages** — every message sent to players lives in `messages.yml` and supports color codes.

## Requirements

- Java 17+
- A Paper/Spigot/Bukkit server running API version 1.19 or newer

## Building

```bash
mvn clean package
```

The shaded jar (including all JDBC drivers and hashing libraries) will be produced in `target/`.

## Installation

1. Drop the built jar into your server's `plugins/` folder.
2. Start the server once to generate `config.yml` and `messages.yml`.
3. Edit `plugins/AuthPlus/config.yml` to configure your database backend and hashing algorithm.
4. Restart or reload the server.

## Commands

| Command | Description | Aliases |
|---|---|---|
| `/register <password>` | Register a new account | |
| `/login <password>` | Log in to your account | |
| `/premium` | Enable premium auto-login (requires a legitimate Minecraft account) | `/autologin` |
| `/cracked` | Disable premium auto-login and switch back to password login | `/unpremium` |
| `/changepassword <current> <new>` | Change your account password | `/changepw` |
| `/unregister [player]` | Unregister your own account, or (with permission) another player's | |
| `/authplus:setlogin` | Set the login spawn location to your current position | `/authplussetlogin` |

## Permissions

| Permission | Description | Default |
|---|---|---|
| `authplus.setlogin` | Allows setting the login spawn location | `op` |
| `authplus.unregister` | Allows unregistering other players | `op` |

## Configuration

### Database

Set the backend in `config.yml` under `data.backend`. File-based backends (`SQLITE`, `H2`) require no extra setup; the rest (`MYSQL`, `MARIADB`, `POSTGRESQL`, `MONGODB`) need connection details (`address`, `port`, `database`, `username`, `password`).

```yaml
data:
  backend: SQLITE
  address: localhost
  port: 0
  database: authplus
  username: root
  password: ''
  table-name: authplus_players
```

### Password hashing

```yaml
hashing:
  algorithm: SHA256   # MD5, SHA256, SHA512, BCRYPT2A, BCRYPT2Y, PBKDF2, ARGON2ID, ARGON2I, ARGON2D
  salt-length: 16
  bcrypt-cost: 12
  pbkdf2-iterations: 600000
  pbkdf2-key-length: 256
  argon2-iterations: 3
  argon2-memory: 65536
  argon2-parallelism: 1
  argon2-hash-length: 32
```

Argon2id and BCrypt are recommended for new installations. Changing the algorithm does not invalidate existing passwords — each hash stores its own algorithm marker and is verified accordingly.

### Login spawn

```yaml
login-spawn:
  enabled: false
  world: world
  x: 0.0
  y: 64.0
  z: 0.0
  yaw: 0.0
  pitch: 0.0
```

Use `/authplus:setlogin` in-game to set this to your current location instead of editing it by hand.

### Other options

```yaml
invisible-mode: true
register-sound: BLOCK_NOTE_BLOCK_PLING
login-sound: ENTITY_PLAYER_LEVELUP
min-password-length: 6
kick-timeout: 60
banned-passwords:
  - password
  - 123456
  ...
```

## Author

Developed by **dzerky_jerky**.
