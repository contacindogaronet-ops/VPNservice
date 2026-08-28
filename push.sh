#!/bin/bash
# JARGO: Auto-Push CI/CD Pipeline (Force-Asset Optimized)

MSG=$1
if [ -z "$MSG" ]; then
    echo "🔥 ERROR: Pesan commit tidak boleh kosong."
    exit 1
fi

# 1. PAKSA PENELANAN BINER: Abaikan semua aturan gitignore untuk folder aset

# 2. Telan sisa kode Java/XML/Gradle
git add .

# 3. Kunci dan Tembakkan
git commit -m "$MSG"
git push -u origin main
