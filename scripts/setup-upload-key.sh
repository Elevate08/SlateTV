#!/usr/bin/env bash
# Run from a private terminal: keytool and GnuPG own all password prompts.
set -euo pipefail
umask 077

usage() {
    cat <<'EOF'
Usage: setup-upload-key.sh create|backup /absolute/key.p12 /absolute/backup.p12.gpg

create: generate a new RSA upload key (alias upload), then encrypt and verify a backup.
backup: encrypt and verify a backup of an existing keystore without changing it.
Keep both paths outside Git, and put the backup on separate, durable storage.
Passwords are entered directly into keytool/GnuPG, never command arguments.
EOF
}
fail() { echo "Error: $*" >&2; exit 1; }
if [[ ${1:-} == --help ]]; then usage; exit 0; fi
[[ $# == 3 && ( $1 == create || $1 == backup ) ]] || { usage >&2; exit 2; }
[[ -t 0 && -t 1 ]] || fail 'Use an interactive terminal for hidden password prompts.'
command -v gpg >/dev/null || fail 'Install GnuPG before creating a key.'
command -v keytool >/dev/null || fail 'Install a JDK providing keytool.'
[[ $2 == /* && $3 == /* ]] || fail 'Use absolute paths.'
key_file=$(realpath -m -- "$2")
backup_file=$(realpath -m -- "$3")
[[ $key_file != "$backup_file" ]] || fail 'Key and backup must be different files.'
[[ ! -e $backup_file && ! -L $backup_file ]] || fail 'Backup already exists; choose a new name.'
[[ -d $(dirname -- "$backup_file") ]] || fail 'Create or mount the private backup directory first.'

# Preserve existing keys wherever they already live; put new files outside Git.
destinations=("$backup_file")
if [[ $1 == create ]]; then destinations+=("$key_file"); fi
for path in "${destinations[@]}"; do
    parent=$(dirname -- "$path")
    while [[ ! -d $parent ]]; do parent=$(dirname -- "$parent"); done
    if git -C "$parent" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        fail 'Store new keystores and encrypted backups outside Git worktrees.'
    fi
done

if [[ $1 == create ]]; then
    [[ ! -e $key_file && ! -L $key_file ]] || fail 'Keystore already exists; use backup to preserve it.'
    mkdir -p -- "$(dirname -- "$key_file")"
    keytool -genkeypair -keystore "$key_file" -storetype PKCS12 \
        -alias upload -keyalg RSA -keysize 4096 -validity 10000
    echo 'New upload key created. Keep it even if the backup step fails.'
else
    [[ -f $key_file && ! -L $key_file ]] || fail 'Existing keystore must be a regular file, not a symlink.'
fi

# Publish only a verified encrypted backup, without overwriting an existing file.
backup_tmp=$(mktemp "$(dirname -- "$backup_file")/.upload-backup.XXXXXXXX.gpg")
trap 'rm -f -- "$backup_tmp"' EXIT
gpg --yes --symmetric --cipher-algo AES256 --output "$backup_tmp" "$key_file"
gpg --decrypt "$backup_tmp" | cmp -s -- "$key_file" -
# Same-directory rename also works on FAT/exFAT backup media without hard links.
mv -nT -- "$backup_tmp" "$backup_file"
[[ ! -e $backup_tmp ]] || fail 'Backup already exists; choose a new name.'
echo 'Encrypted backup created and decrypted contents verified against the keystore.'
echo 'Store the backup passphrase and keystore password in your password manager.'
echo 'Disconnect off-device backup storage after confirming it is recoverable.'
