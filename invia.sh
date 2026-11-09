#!/data/data/com.termux/files/usr/bin/bash
cd ~/jarvis-app || exit 1
git pull --rebase -q 2>/dev/null
git add -A
git commit -m "${1:-aggiornamento}" -q
git push -q && echo "Inviato. Controlla la compilazione con: gh run list -L 3"
