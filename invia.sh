#!/data/data/com.termux/files/usr/bin/bash
cd ~/jarvis-app || exit 1
git add -A
git commit -m "${1:-aggiornamento}" -q
git pull --rebase -q || { echo "Errore nel pull: mandami lo screenshot"; exit 1; }
git push -q && echo "Inviato. Controlla la compilazione con: gh run list -L 3"
