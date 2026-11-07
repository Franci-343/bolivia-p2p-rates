#!/bin/bash
# ==============================================================================
# Setup Script para Servidor Oracle Cloud (Ubuntu 24.04 noble - 1 OCPU, 1 GB RAM)
# ==============================================================================
set -e

echo "=== 1. Instalando Eclipse Temurin 25 (Adoptium / Eclipse Foundation) y binutils ==="
if java --version 2>&1 | grep -q "Temurin-25"; then
    echo "Eclipse Temurin 25 ya está instalado:"
    java --version
    sudo apt-get update && sudo apt-get install -y binutils
else
    sudo apt-get update
    sudo apt-get install -y wget apt-transport-https gpg binutils

    # Clave GPG oficial de Adoptium
    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null

    # Repositorio oficial de Adoptium para Ubuntu
    echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list

    sudo apt-get update
    sudo apt-get install -y temurin-25-jdk binutils

    echo "Eclipse Temurin 25 instalado con éxito:"
    java --version
fi

echo "=== 2. Creando directorios de la aplicación ==="
mkdir -p /home/ubuntu/bolivia-p2p-rates/database
chmod 755 /home/ubuntu/bolivia-p2p-rates

echo "=== 3. Permisos sudoers para reinicio sin contraseña en CI/CD ==="
echo "ubuntu ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart bolivia-rates, /usr/bin/systemctl status bolivia-rates, /usr/bin/systemctl daemon-reload, /usr/bin/systemctl enable bolivia-rates, /usr/bin/cp -f /home/ubuntu/bolivia-p2p-rates/bolivia-rates.service /etc/systemd/system/bolivia-rates.service" | sudo tee /etc/sudoers.d/bolivia-rates
sudo chmod 440 /etc/sudoers.d/bolivia-rates

echo "=== 4. Construyendo Custom Minimal JRE con jlink ==="
if [ -f "/home/ubuntu/bolivia-p2p-rates/build-custom-jre.sh" ]; then
    chmod +x /home/ubuntu/bolivia-p2p-rates/build-custom-jre.sh
    /home/ubuntu/bolivia-p2p-rates/build-custom-jre.sh
fi

echo "=== 5. Configurando Servicio systemd con Custom JRE ==="
cat << EOF | sudo tee /etc/systemd/system/bolivia-rates.service
[Unit]
Description=Bolivia P2P Rates Monitor Service
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/bolivia-p2p-rates
ExecStart=/home/ubuntu/bolivia-p2p-rates/jre/bin/java -Xms64m -Xmx256m -XX:MaxMetaspaceSize=80m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -jar /home/ubuntu/bolivia-p2p-rates/bolivia-p2p-rates.jar
Restart=always
RestartSec=5
Environment=PORT=8080
Environment=POLL_INTERVAL_SECONDS=600
Environment=SQLITE_DB_PATH=database/rates.db
Environment=FIAT_CURRENCY=BOB
Environment=ASSET_CURRENCY=USDT
Environment=PUBLIC_BASE_PATH=/bolivia-p2p-rates
MemoryMax=450M

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable bolivia-rates

echo "=== 6. Abriendo Puerto 8080 en el Firewall iptables de Ubuntu ==="
# Las imágenes Ubuntu de Oracle Cloud tienen reglas iptables que bloquean puertos no estándar
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8080 -j ACCEPT 2>/dev/null || true
if which netfilter-persistent >/dev/null 2>&1; then
    sudo netfilter-persistent save
fi

echo "=== Configuración del servidor completada con éxito ==="
echo "Ahora puedes ejecutar el pipeline de GitHub Actions."
