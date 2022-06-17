#!/bin/bash

set -o nounset \
    -o errexit \
    -o verbose \
    -o xtrace

base_name="wsproxytest"
base_name_upper=`echo $base_name | tr '[:lower:]' '[:upper:]'`
base_hostname="scalytica.net"
wsproxy_hostname="$base_name.$base_hostname"

PW="scalytica"

##
# Generate self-signed CA files
##

# Create a self signed key pair root CA certificate.
keytool -genkeypair -v \
  -alias "$base_name.ca" \
  -dname "CN=ca.$base_hostname,OU=Development,O=Scalytica,L=Heggedal,S=Akershus,C=NO" \
  -keystore "$base_name.ca.jks" \
  -keypass $PW \
  -storepass $PW \
  -keyalg RSA \
  -keysize 4096 \
  -ext KeyUsage:critical="keyCertSign" \
  -ext BasicConstraints:critical="ca:true" \
  -validity 9999

# Export the exampleCA public certificate as exampleca.crt so that it can be used in trust stores.
keytool -export -v \
  -alias "$base_name.ca" \
  -file "$base_name.ca.crt" \
  -keypass $PW \
  -storepass $PW \
  -keystore "$base_name.ca.jks" \
  -rfc

##
# Generate self-signed server files
##

# Create a server certificate, tied to host url
keytool -genkeypair -v \
  -alias "$wsproxy_hostname" \
  -dname "CN=$wsproxy_hostname,OU=Development,O=Scalytica,L=Heggedal,S=Akershus,C=NO" \
  -keystore "$wsproxy_hostname.jks" \
  -keypass $PW \
  -storepass $PW \
  -keyalg RSA \
  -keysize 2048 \
  -validity 385

# Create a certificate signing request for the host url
keytool -certreq -v \
  -alias "$wsproxy_hostname" \
  -keypass $PW \
  -storepass $PW \
  -keystore "$wsproxy_hostname.jks" \
  -file "$wsproxy_hostname.csr"

# Tell the CA to sign the server certificate. Note that the extension is on the
# request, and not the original certificate.
# Technically, keyUsage should be:
#   - digitalSignature for DHE or ECDHE,
#   - keyEncipherment for RSA.
keytool -gencert -v \
  -alias "$base_name.ca" \
  -keypass $PW \
  -storepass $PW \
  -keystore "$base_name.ca.jks" \
  -infile "$wsproxy_hostname.csr" \
  -outfile "$wsproxy_hostname.crt" \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:$wsproxy_hostname" \
  -rfc

# Tell jks it can trust the self-signed CA as a signer.
keytool -import -v \
  -alias "$base_name.ca" \
  -file "$base_name.ca.crt" \
  -keystore "$wsproxy_hostname.jks" \
  -storetype JKS \
  -storepass $PW << EOF
yes
EOF

# Import the signed certificate back into the server jks
keytool -import -v \
  -alias "$wsproxy_hostname" \
  -file "$wsproxy_hostname.crt" \
  -keystore "$wsproxy_hostname.jks" \
  -storetype JKS \
  -storepass $PW

# List out the contents of the server jks just to confirm it.
# This is the key store you should present as the server.
#keytool -list -v \
#  -keystore "$wsproxy_hostname.jks" \
#  -storepass $PW

##
# Generate trust store for clients
##

# Create a JKS keystore that trusts the self-signed CA.
keytool -import -v \
  -alias "$base_name.ca" \
  -file "$base_name.ca.crt" \
  -keypass $PW \
  -storepass $PW \
  -keystore "$wsproxy_hostname.truststore.jks" << EOF
yes
EOF

# List out the details of the store password.
keytool -list -v \
  -keystore "$wsproxy_hostname.truststore.jks" \
  -storepass $PW

##########
echo "All done..."