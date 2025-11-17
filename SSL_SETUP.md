# SSL Certificate Setup for FIX Tool

This guide explains how to set up SSL/TLS certificates for secure FIX connections.

## Prerequisites

- **OpenSSL**: For extracting server certificates
  - macOS: Usually pre-installed
  - Linux: `sudo apt-get install openssl` or `sudo yum install openssl`
  - Windows: Download from [OpenSSL website](https://www.openssl.org/)

- **Java JDK**: For creating Java keystores
  - Download from [Oracle](https://www.oracle.com/java/technologies/downloads/) or [OpenJDK](https://openjdk.org/)
  - Verify installation: `java -version` and `keytool -help`

## Quick Start

### Option 1: Using the Automated Script (Recommended)

1. Run the setup script:
   ```bash
   ./setup_ssl_certs.sh
   ```

2. Follow the prompts:
   - Enter the FIX server hostname (e.g., `fix.example.com`)
   - Enter the port (default: 443)
   - Enter and confirm a truststore password

3. The script will:
   - Extract the SSL certificate from the server
   - Create a Java truststore at `~/.fixtool/certs/truststore.jks`
   - Display configuration instructions

### Option 2: Manual Setup

If you prefer to set up certificates manually:

#### Step 1: Extract Server Certificate

```bash
mkdir -p ~/.fixtool/certs
openssl s_client -connect fix.example.com:443 -showcerts </dev/null 2>/dev/null | \
  openssl x509 -outform PEM > ~/.fixtool/certs/server.crt
```

#### Step 2: Create Truststore

```bash
keytool -import -alias fix-server \
  -file ~/.fixtool/certs/server.crt \
  -keystore ~/.fixtool/certs/truststore.jks \
  -storepass YOUR_PASSWORD \
  -noprompt
```

## Configuring FIX Tool

### Using the UI

1. Open FIX Tool application
2. Go to Connection Panel
3. Create or edit a connection profile
4. Configure SSL settings:
   - **Host**: Your FIX server hostname
   - **Port**: Your FIX server port (usually 443)
   - **Use SSL/TLS**: ✓ Check this option
   - **Trust Store Path**: `~/.fixtool/certs/truststore.jks`
   - **Trust Store Password**: The password you created
5. Save and connect

### Manual Configuration

Edit `~/.fixtool/connection_profiles.json` and add to your profile's config:

```json
{
  "host": "fix.example.com",
  "port": "443",
  "useSSL": true,
  "trustStorePath": "/Users/youruser/.fixtool/certs/truststore.jks",
  "trustStorePassword": "your-password-here"
}
```

## Client Certificate Authentication

Some FIX servers require client certificates for mutual TLS authentication.

### If you have a PKCS12 (.p12) file:

```bash
# Use the client certificate directly
keytool -list -keystore client-cert.p12 -storetype PKCS12
```

Configure in FIX Tool:
- **Key Store Path**: `/path/to/client-cert.p12`
- **Key Store Password**: Your certificate password
- **Key Store Type**: `PKCS12`

### If you have separate certificate and key files:

Convert to PKCS12 format first:

```bash
openssl pkcs12 -export \
  -in client-cert.crt \
  -inkey client-key.key \
  -out client-cert.p12 \
  -name "fix-client"
```

## Troubleshooting

### Connection hangs at "Connecting..."

**Cause**: Missing or incorrect truststore configuration

**Solution**:
- Verify truststore path is correct
- Check truststore password
- Ensure certificate was extracted from the correct server

### "keystore not found, using empty keystore" warning

**Cause**: QuickFIX/J can't find the truststore file

**Solution**:
- Verify the file exists: `ls -l ~/.fixtool/certs/truststore.jks`
- Check the path in your profile configuration
- Ensure you've saved the profile after adding SSL settings

### SSL handshake failure

**Possible causes**:
- Server certificate doesn't match hostname
- Certificate has expired
- Server requires client certificate (mutual TLS)
- Protocol mismatch (TLS version)

**Solutions**:
1. Verify certificate details:
   ```bash
   openssl x509 -in ~/.fixtool/certs/server.crt -noout -text
   ```

2. Check if server requires client cert:
   ```bash
   openssl s_client -connect fix.example.com:443
   ```
   Look for "Acceptable client certificate CA names" in the output

3. If mutual TLS is required, configure a client certificate (see above)

### Can't connect to server during certificate extraction

**Solutions**:
- Check if you need VPN access
- Verify firewall rules allow outbound connections
- Test basic connectivity: `telnet fix.example.com 443`
- Try with curl: `curl -v https://fix.example.com:443`

## Security Best Practices

1. **Use strong passwords**: Don't use "changeit" or simple passwords
2. **Protect your keystores**: Set file permissions to 600
   ```bash
   chmod 600 ~/.fixtool/certs/*.jks
   ```
3. **Never commit certificates or keystores**: Add to `.gitignore`:
   ```
   *.jks
   *.p12
   *.crt
   *.key
   ```
4. **Rotate credentials**: Update certificates before expiration
5. **Use environment-specific certificates**: Don't share production certs across environments

## Additional Resources

- [QuickFIX/J SSL Configuration](https://www.quickfixj.org/usermanual/2.3.0/usage/configuration.html)
- [Java Keytool Documentation](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html)
- [OpenSSL Commands](https://www.openssl.org/docs/man1.1.1/man1/openssl.html)

## Support

For issues or questions:
- Open an issue on GitHub
- Check existing issues for solutions
- Provide logs and error messages for faster troubleshooting
