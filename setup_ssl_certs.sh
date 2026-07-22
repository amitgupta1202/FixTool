#!/bin/bash

# Script to set up SSL certificates for FIX connections
# This will extract the server certificate and create a truststore

set -e

echo "=== FIX Tool SSL Certificate Setup ==="
echo ""
echo "This script will extract an SSL certificate from a FIX server"
echo "and create a Java truststore for secure connections."
echo ""

# Prompt for server details
read -p "Enter FIX server hostname (e.g., fix.example.com): " SERVER_HOST

# Validate server host is not empty
if [ -z "$SERVER_HOST" ]; then
    echo "✗ Server hostname cannot be empty. Exiting."
    exit 1
fi

read -p "Enter FIX server port [443]: " SERVER_PORT
SERVER_PORT=${SERVER_PORT:-443}

# Validate port is a number
if ! [[ "$SERVER_PORT" =~ ^[0-9]+$ ]] || [ "$SERVER_PORT" -lt 1 ] || [ "$SERVER_PORT" -gt 65535 ]; then
    echo "✗ Invalid port number. Must be between 1 and 65535. Exiting."
    exit 1
fi

echo ""
echo "Server: $SERVER_HOST:$SERVER_PORT"

# Configuration
FIXTOOL_DIR="$HOME/.fixtool"
CERT_DIR="$FIXTOOL_DIR/certs"
CERT_FILE="$CERT_DIR/server.crt"
TRUSTSTORE_FILE="$CERT_DIR/truststore.jks"

echo "Certificate directory: $CERT_DIR"
echo ""

# Create directory if it doesn't exist
mkdir -p "$CERT_DIR"

# Prompt for truststore password
echo "Please enter a password for the truststore (will be hidden):"
read -s -p "Password: " TRUSTSTORE_PASSWORD
echo ""
read -s -p "Confirm password: " TRUSTSTORE_PASSWORD_CONFIRM
echo ""

# Verify passwords match
if [ "$TRUSTSTORE_PASSWORD" != "$TRUSTSTORE_PASSWORD_CONFIRM" ]; then
    echo "✗ Passwords do not match. Exiting."
    exit 1
fi

# Verify password is not empty
if [ -z "$TRUSTSTORE_PASSWORD" ]; then
    echo "✗ Password cannot be empty. Exiting."
    exit 1
fi

echo "✓ Password set successfully"
echo ""

# Step 1: Extract the server certificate
echo "Step 1: Extracting SSL certificate from server..."
if command -v openssl &> /dev/null; then
    echo "Connecting to $SERVER_HOST:$SERVER_PORT..."

    # Try to get the certificate
    if openssl s_client -connect "$SERVER_HOST:$SERVER_PORT" -showcerts </dev/null 2>/dev/null | openssl x509 -outform PEM > "$CERT_FILE" 2>/dev/null; then
        echo "✓ Certificate extracted successfully to: $CERT_FILE"

        # Show certificate details
        echo ""
        echo "Certificate details:"
        openssl x509 -in "$CERT_FILE" -noout -subject -issuer -dates
        echo ""
    else
        echo "✗ Failed to extract certificate. The server might not be reachable or doesn't use standard SSL."
        echo ""
        echo "Please check:"
        echo "  1. Is the server address correct? ($SERVER_HOST:$SERVER_PORT)"
        echo "  2. Can you reach the server from your network?"
        echo "  3. Does the server require a VPN connection?"
        exit 1
    fi
else
    echo "✗ openssl command not found. Please install OpenSSL first."
    exit 1
fi

# Step 2: Create truststore
echo "Step 2: Creating Java truststore..."
if command -v keytool &> /dev/null; then
    # Remove existing truststore if it exists
    if [ -f "$TRUSTSTORE_FILE" ]; then
        echo "Removing existing truststore..."
        rm "$TRUSTSTORE_FILE"
    fi

    # Import certificate into truststore
    keytool -import -alias fixtool-venue -file "$CERT_FILE" \
        -keystore "$TRUSTSTORE_FILE" \
        -storepass "$TRUSTSTORE_PASSWORD" \
        -noprompt

    echo "✓ Truststore created successfully: $TRUSTSTORE_FILE"
    echo ""
else
    echo "✗ keytool command not found. Please install Java JDK first."
    exit 1
fi

# Step 3: Print configuration instructions
echo "=== Setup Complete ==="
echo ""
echo "Certificate successfully extracted from: $SERVER_HOST:$SERVER_PORT"
echo "Truststore created at: $TRUSTSTORE_FILE"
echo ""
echo "Next steps:"
echo "1. Open your FIX Tool application"
echo "2. Create or edit an SSL-enabled connection profile"
echo "3. Configure the following SSL settings:"
echo ""
echo "   Host: $SERVER_HOST"
echo "   Port: $SERVER_PORT"
echo "   Use SSL/TLS: ✓ (checked)"
echo "   Trust Store Path: $TRUSTSTORE_FILE"
echo "   Trust Store Password: (the password you just entered)"
echo ""
echo "Or manually edit the profile JSON file:"
echo "   File: $FIXTOOL_DIR/connection_profiles.json"
echo ""
echo "Add these fields to your SSL profile's config:"
echo '   "host": "'"$SERVER_HOST"'",'
echo '   "port": "'"$SERVER_PORT"'",'
echo '   "useSSL": true,'
echo '   "trustStorePath": "'"$TRUSTSTORE_FILE"'",'
echo '   "trustStorePassword": "<your-password-here>"'
echo ""
echo "IMPORTANT: Remember the password you entered - you'll need it to configure the profile!"
echo ""
echo "If the server requires client authentication, you'll also need to:"
echo "   1. Obtain your client certificate (.p12 or .jks file)"
echo "   2. Add keyStorePath and keyStorePassword to the profile"
echo ""
