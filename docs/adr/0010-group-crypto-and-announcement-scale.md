# Group cryptography and announcement scale

Status: retain the gated QA design; qualify an established MLS implementation
before a production group-crypto migration. This does not approve a release.

The current design uses signed owner policies, explicit capabilities, and fresh
encrypted group keys after membership changes. Delegated membership actions
pause new content until the owner incorporates them into a fresh policy/epoch.
It does not establish full forward secrecy or post-compromise security. Owner
absence can delay admission and key changes. There is no safe owner-recovery
mechanism yet; silently granting another device ownership would weaken the
existing trust boundary.

Two maintained RFC 9420 implementations were reviewed on 6 October 2026:

| Candidate                                                              | Evidence                                                                                                                                                                   | Qualification still needed                                                                                                      |
| ---------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| [OpenMLS](https://github.com/openmls/openmls)                          | Rust implementation maintained by Phoenix R&D and CE Labs. Its platform table lists Android targets as built in CI, unsupported and not tested.                            | Rust/Kotlin FFI packaging, Android lifecycle and storage tests, provider choice, interoperability and release/audit provenance. |
| [mls-rs](https://github.com/awslabs/mls-rs/blob/main/mls-rs/README.md) | Rust implementation with transport-independent group state and pluggable storage/crypto. The README explicitly says it has not received a full third-party security audit. | Android artifact/FFI qualification, provider licensing and deployment, storage rollback resistance and independent review.      |

Choose OpenMLS for a separate Android qualification spike, with mls-rs as the
comparison candidate. This is an evaluation decision, not a production library
selection. Do not implement MLS primitives in Kotlin or JavaScript. Before
changing the wire protocol, test credential binding to the existing stable
participant identity, Keystore custody, crash recovery, atomic epoch commits,
offline forks, lost/replayed commits, membership removal, interoperability,
version upgrades and realistic battery/memory costs. Preserve the old protocol
only through an explicit, bounded migration window.

Keep the existing 16-member QA ceiling. MLS alone would not make huge nearby
rosters cheap. A future large announcement audience needs a separate signed
publication/subscription model, bounded inventories and history, and a deliberate
decision about public versus private audience confidentiality. Do not replicate
thousands of wrapped keys through every carrier or describe small-group QA
channels as a production broadcast service.

Private groups restrict participation to approved cryptographic members and
encrypt eligible content. Membership removal cannot erase copies, screenshots
or keys already received. Chat remains behind the independent security/release
gate documented in the existing Android and private-chat worklogs.
