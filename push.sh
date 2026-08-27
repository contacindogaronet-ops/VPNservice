#!/bin/bash
# JARGO: Auto-Push CI/CD Pipeline (SSH Optimized)

MSG=$1
if [ -z "$MSG" ]; then
    echo "🔥 ERROR: Pesan commit tidak boleh kosong."
    echo "Eksekusi: ./push.sh \"first commit: inisialisasi arsitektur neural vpn\""
    exit 1
fi

# Injeksi repositori jika belum terhubung
if [ ! -d ".git" ]; then
    git init
    git branch -M main
    git remote add origin git@github.com:contacindogaronet-ops/VPNservice.git
fi

git add .
git commit -m "$MSG"
git push -u origin main
