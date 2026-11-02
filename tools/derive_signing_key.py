#!/usr/bin/env python3
"""
Derive the SAME APK signing key on every GitHub build from one secret passphrase.

Android only installs an update if it is signed with the same certificate as the installed app.
GitHub runners are wiped after each build, so instead of storing a keystore file we rebuild it
deterministically from the AIRSCOPE_SIGNING_SEED repository secret:

  seed --HMAC-DRBG--> two RSA-1536 primes -> RSA-3072 key
  fixed subject / serial / validity + deterministic PKCS#1 v1.5 signature -> identical certificate

The private key never leaves the runner and is never written to the repository.
Usage: derive_signing_key.py <out.p12>   (reads AIRSCOPE_SIGNING_SEED; prints the keystore password)
"""
import datetime
import hashlib
import hmac
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID


class Drbg:
    """HMAC-DRBG (SHA-256), NIST SP 800-90A style, no reseeding."""

    def __init__(self, seed: bytes):
        self.k = b"\x00" * 32
        self.v = b"\x01" * 32
        self._update(seed)

    def _update(self, data: bytes = b""):
        self.k = hmac.new(self.k, self.v + b"\x00" + data, hashlib.sha256).digest()
        self.v = hmac.new(self.k, self.v, hashlib.sha256).digest()
        if data:
            self.k = hmac.new(self.k, self.v + b"\x01" + data, hashlib.sha256).digest()
            self.v = hmac.new(self.k, self.v, hashlib.sha256).digest()

    def bytes(self, n: int) -> bytes:
        out = b""
        while len(out) < n:
            self.v = hmac.new(self.k, self.v, hashlib.sha256).digest()
            out += self.v
        self._update()
        return out[:n]

    def int_bits(self, bits: int) -> int:
        return int.from_bytes(self.bytes(bits // 8), "big")


SMALL_PRIMES = [p for p in range(3, 2000) if all(p % q for q in range(2, int(p ** 0.5) + 1))]


def is_probable_prime(n: int, rng: Drbg, rounds: int = 48) -> bool:
    if n < 2:
        return False
    for p in SMALL_PRIMES:
        if n % p == 0:
            return n == p
    d, r = n - 1, 0
    while d % 2 == 0:
        d //= 2
        r += 1
    for _ in range(rounds):
        a = 2 + rng.int_bits(n.bit_length() + 64) % (n - 3)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def gen_prime(rng: Drbg, bits: int, e: int) -> int:
    while True:
        c = rng.int_bits(bits) | (1 << (bits - 1)) | (1 << (bits - 2)) | 1  # top two bits set, odd
        if (c - 1) % e != 0 and is_probable_prime(c, rng):
            return c


def derive(seed: str) -> rsa.RSAPrivateKey:
    rng = Drbg(b"AirScope APK signing key v1|" + seed.encode())
    e = 65537
    while True:
        p = gen_prime(rng, 1536, e)
        q = gen_prime(rng, 1536, e)
        if p == q:
            continue
        if p < q:
            p, q = q, p
        n = p * q
        if n.bit_length() != 3072:
            continue
        phi = (p - 1) * (q - 1)
        d = pow(e, -1, phi)
        nums = rsa.RSAPrivateNumbers(
            p=p, q=q, d=d, dmp1=d % (p - 1), dmq1=d % (q - 1), iqmp=pow(q, -1, p),
            public_numbers=rsa.RSAPublicNumbers(e, n),
        )
        return nums.private_key()


def certificate(key: rsa.RSAPrivateKey, seed: str) -> x509.Certificate:
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "AirScope Personal"),
                      x509.NameAttribute(NameOID.ORGANIZATION_NAME, "K")])
    serial = int.from_bytes(hashlib.sha256(b"serial|" + seed.encode()).digest()[:15], "big") | 1
    start = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    end = datetime.datetime(2076, 1, 1, tzinfo=datetime.timezone.utc)
    return (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(serial)
            .not_valid_before(start).not_valid_after(end)
            .sign(key, hashes.SHA256()))  # PKCS#1 v1.5: deterministic


def main():
    seed = os.environ.get("AIRSCOPE_SIGNING_SEED", "")
    if len(seed) < 16:
        sys.exit("AIRSCOPE_SIGNING_SEED must be at least 16 characters")
    out = sys.argv[1]
    key = derive(seed)
    cert = certificate(key, seed)
    password = hashlib.sha256(b"keystore-password|" + seed.encode()).hexdigest()[:32]
    data = pkcs12.serialize_key_and_certificates(
        b"airscope", key, cert, None, serialization.BestAvailableEncryption(password.encode()))
    with open(out, "wb") as f:
        f.write(data)
    fp = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    print(password)
    print("certificate SHA-256: " + fp, file=sys.stderr)


if __name__ == "__main__":
    main()
