# Anonymous Profile Rebuild Job

Standalone Spring Boot job for reconstructing anonymous-profile history from demographic and biometric UIN history data.

## Purpose

For every eligible UIN history record, the job must reconstruct:

- Demographic identity details from encrypted `idrepo.uin_h.uin_data`.
- Biometric metadata from the corresponding `idrepo.uin_biometric_h` record and CBEFF object.
- The previous and current profile versions required for an anonymous-profile event.

Both demographic and biometric reconstruction are mandatory. A profile must not be stored when either part cannot be reconstructed successfully.

## Data Sources

### Source database

The job reads:

- `idrepo.uin_h` for encrypted demographic identity history.
- `idrepo.uin_biometric_h` for biometric history and `bio_file_id`.
- The applicable UIN/hash information required to locate biometric objects.

The source database account should have read-only access.

### Object store

The job retrieves the CBEFF object associated with the matched biometric-history row.

The CBEFF data is parsed to derive biometric metadata such as:

- `type`
- `subType`
- `qualityScore`

Raw biometric images and biometric templates must not be written into the anonymous profile.

### Target database

The reconstructed profile is inserted into:

```text
idrepo.anonymous_profile
```

## Reconstruction Flow

For each UIN reference:

1. Read active `uin_h` records in deterministic chronological order.
2. Decrypt `uin_data` through Key Manager.
3. Decode and validate the demographic identity JSON.
4. Find the matching `uin_biometric_h` record for the same `uin_ref_id`.
5. Select a non-future biometric record whose timestamp is within the configured matching window.
6. Use its `bio_file_id` and required UIN/hash information to retrieve the CBEFF object.
7. Parse the CBEFF object and extract the required biometric metadata.
8. Combine the demographic identity and biometric metadata into the reconstructed profile.
9. Build a `New` event for the first history record or an `Update` event for subsequent records.
10. Insert the completed event into `idrepo.anonymous_profile`.

The intended flow is:

```text
uin_h record
    |
    +-- decrypt uin_data
    |       |
    |       +-- validate demographic identity
    |
    +-- match uin_biometric_h record
            |
            +-- retrieve CBEFF from object store
                    |
                    +-- extract biometric metadata
                              |
                              v
                build complete anonymous profile
                              |
                              v
                insert into anonymous_profile
```

## Profile Format

The first history entry for a UIN is stored as:

```json
{
  "processName": "New",
  "date": "YYYY-MM-DD",
  "oldProfile": null,
  "newProfile": {
    "demographicDetails": {},
    "biometricInfo": []
  }
}
```

Each later history entry is stored as:

```json
{
  "processName": "Update",
  "date": "YYYY-MM-DD",
  "oldProfile": {
    "demographicDetails": {},
    "biometricInfo": []
  },
  "newProfile": {
    "demographicDetails": {},
    "biometricInfo": []
  }
}
```

The exact demographic structure should follow the decrypted MOSIP identity schema.

## Biometric Matching Rules

Biometric reconstruction is mandatory.

A biometric-history record must:

- Belong to the same `uin_ref_id` as the demographic-history record.
- Not have a timestamp later than the demographic-history record.
- Fall within the configured matching window.
- Reference an accessible and parseable CBEFF object.

Biometric information from an unrelated or earlier profile version must not be reused as a fallback.

If no matching biometric record exists, the CBEFF object cannot be retrieved, or the CBEFF content cannot be parsed, the profile must be rejected and the failure must be reported. The job must not silently write an empty `biometricInfo` array.

## History Ordering

Records must be processed in a deterministic order:

```sql
ORDER BY uin_ref_id ASC, eff_dtimes ASC, id ASC
```

The history row ID is required as a final tie-breaker when multiple records have the same effective timestamp.

For each UIN:

- The first reconstructed record uses `processName: "New"`.
- Later records use `processName: "Update"`.
- `oldProfile` contains the immediately preceding successfully reconstructed history version.
- `newProfile` contains the current reconstructed demographic and biometric details.

## Failure Handling

The job must fail closed.

A record must not be inserted when any of these operations fails:

- Demographic decryption
- Demographic JSON decoding or validation
- Biometric-history matching
- Object-store retrieval
- CBEFF parsing
- Profile construction
- Target database insertion

Failures should record the affected UIN-history row, processing stage, and error details without logging decrypted demographic data, raw biometric data, credentials, or production identifiers.

A failed history record must not be skipped while later records for the same UIN continue with an incorrect `oldProfile`.

## Configuration

Configure source and target database connections using environment variables or deployment secrets referenced by `application-default.properties`.

Required functional settings include:

```properties
rebuild.biometric-info.enabled=true
rebuild.biometric-info.match-window-seconds=5
```

Because biometric reconstruction is mandatory, the application should refuse to start when:

```properties
rebuild.biometric-info.enabled=false
```

Key Manager, Auth Manager, database credentials, and object-store credentials must be supplied through secure deployment configuration. Do not commit real credentials to this repository.

## Safety

Keep dry-run mode enabled until database access, Key Manager access, biometric-history matching, object-store retrieval, and CBEFF parsing have all been verified:

```properties
rebuild.dry-run=true
```

Dry-run mode should perform the complete reconstruction and validation flow without inserting records into the target database.

## Run

From the repository root:

```bash
mvn clean verify
mvn spring-boot:run -Dspring-boot.run.profiles=default
```

## Current Implementation Status

The current application implements:

- Reading ordered records from `idrepo.uin_h`.
- Decrypting demographic identity data through Key Manager.
- Building `New` and `Update` envelopes.
- Inserting envelopes into `idrepo.anonymous_profile`.

The following mandatory functionality still needs to be implemented before the job is complete:

- Reading and matching `idrepo.uin_biometric_h`.
- Retrieving CBEFF objects from the ID Repository object store.
- Parsing CBEFF biometric metadata.
- Combining demographic and biometric information into the required anonymous-profile schema.
- Rejecting incomplete demographic or biometric reconstruction.
- Automated tests covering the complete reconstruction flow.
