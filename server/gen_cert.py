# -*- coding: utf-8 -*-
"""
生成 Chathyx 聊天服务器自签 TLS 证书（RSA 2048，有效期 10 年）。
输出到 certs/：
  cert.pem  服务器证书（公钥，可随客户端分发做证书锁定）
  key.pem   服务器私钥（敏感，禁止提交，已在 .gitignore 忽略）
运行：python gen_cert.py
"""

import datetime
import ipaddress
import os

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CERT_DIR = os.path.join(BASE_DIR, "certs")
os.makedirs(CERT_DIR, exist_ok=True)

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([
    x509.NameAttribute(NameOID.COUNTRY_NAME, "CN"),
    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Chathyx"),
    x509.NameAttribute(NameOID.COMMON_NAME, "Chathyx Chat Server"),
])

cert = (
    x509.CertificateBuilder()
    .subject_name(name)
    .issuer_name(name)
    .public_key(key.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(datetime.datetime.now(datetime.timezone.utc)
                      - datetime.timedelta(days=1))
    .not_valid_after(datetime.datetime.now(datetime.timezone.utc)
                     + datetime.timedelta(days=3650))
    .add_extension(
        x509.SubjectAlternativeName([
            x509.DNSName("localhost"),
            x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
            x509.IPAddress(ipaddress.ip_address("192.168.100.124")),
        ]),
        critical=False,
    )
    .add_extension(x509.BasicConstraints(ca=True, path_length=None),
                   critical=True)
    .sign(key, hashes.SHA256())
)

with open(os.path.join(CERT_DIR, "key.pem"), "wb") as f:
    f.write(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption()))

with open(os.path.join(CERT_DIR, "cert.pem"), "wb") as f:
    f.write(cert.public_bytes(serialization.Encoding.PEM))

print("自签证书已生成: %s" % CERT_DIR)
