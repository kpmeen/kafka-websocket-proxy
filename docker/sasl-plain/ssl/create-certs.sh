#!/bin/bash

# set -o nounset -o errexit -o errtrace -o functrace

exec_dir=$(pwd);
this_dir=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd );

BASE_HOSTNAME="sandbox.kafka.no";
STOREPASS="sandbox";

declare -a SANDBOX_HOSTS=( kafka1 client );

function createCertFiles() {
	local there=$(pwd);
	cd $this_dir;
	local the_host=$1;

	echo "Creating SSL files for $the_host...";

	# Create key stores
	keytool \
		-genkey \
		-noprompt \
		-alias $the_host \
		-dname "CN=$the_host.$BASE_HOSTNAME/OU=TEST/ O=KAFKA-SANDBOX/L=Hobbiton/S=Shire/C=WF" \
		-keystore $the_host.keystore.jks \
		-keyalg RSA \
		-storepass $STOREPASS \
		-keypass $STOREPASS;

	# Create CSR, sign the key and import back into keystore
	keytool \
		-certreq \
		-alias $the_host \
		-keystore $the_host.keystore.jks \
		-file $the_host.csr \
		-storepass $STOREPASS \
		-keypass $STOREPASS;

	openssl x509 \
		-req \
		-CA test-ca-1.crt \
		-CAkey test-ca-1.key \
		-in $the_host.csr \
		-out $the_host-ca1-signed.crt \
		-days 9999 \
		-CAcreateserial \
		-passin "pass:$STOREPASS";

	keytool \
		-import \
		-alias CARoot \
		-file test-ca-1.crt \
		-keystore $the_host.keystore.jks \
		-storepass $STOREPASS \
		-keypass $STOREPASS << EOF
yes
EOF

	keytool \
		-import \
		-alias $the_host \
		-file $the_host-ca1-signed.crt \
		-keystore $the_host.keystore.jks \
		-storepass $STOREPASS \
		-keypass $STOREPASS << EOF
yes
EOF

	# Create truststore and import the CA cert.
	keytool \
		-import \
		-alias CARoot \
		-file test-ca-1.crt \
		-keystore $the_host.truststore.jks \
		-storepass $STOREPASS \
		-keypass $STOREPASS << EOF
yes
EOF

	echo "$STOREPASS" > ${the_host}_sslkey_creds;
	echo "$STOREPASS" > ${the_host}_keystore_creds;
	echo "$STOREPASS" > ${the_host}_truststore_creds;

	echo "Completed creating SSL files for $the_host.";
	cd $there;
}

function cleanRedundantFiles() {
	cd $this_dir;
	rm -f *.csr *.srl *.key *.crt;
	cd $exec_dir;
}

function createCerts() {
	cd $this_dir;

	# Generate CA (Certificate Authority) key
	openssl req \
		-new \
		-x509 \
		-keyout test-ca-1.key \
		-out test-ca-1.crt \
		-days 365 \
		-subj "/CN=ca1.$BASE_HOSTNAME/OU=TEST/ O=KAFKA-SANDBOX/L=Hobbiton/S=Shire/C=WF" \
		-passin "pass:$STOREPASS" \
		-passout "pass:$STOREPASS";

	# Loop over the "hosts" below to generate key stores and certs
	# If adding more brokers in the docker-compose, these must also be added here
	echo $SANDBOX_HOSTS
	for i in "${SANDBOX_HOSTS[@]}"
	do
		echo $i
		createCertFiles $i;
	done

	echo "Cleaning up...";

	cleanRedundantFiles;
	cd $exec_dir;
}

function deleteCerts() {
	cleanRedundantFiles;
	cd $this_dir;
	rm -f *_creds *.jks;
	cd $exec_dir;
}

function recreateCerts() {
	deleteCerts;
	createCerts;
}
