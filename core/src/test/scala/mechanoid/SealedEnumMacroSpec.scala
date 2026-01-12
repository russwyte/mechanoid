package mechanoid

import zio.*
import zio.test.*
import mechanoid.core.{CaseHasher, MState, MEvent, SealedEnum}
import mechanoid.macros.SealedEnumMacros
import mechanoid.macros.SealedEnumMacros.CaseInfo

object SealedEnumMacroSpec extends ZIOSpecDefault:

  // Test sealed trait with case classes
  sealed trait TestSealedTrait    extends MState
  case class StateA(value: Int)   extends TestSealedTrait
  case class StateB(name: String) extends TestSealedTrait
  case object StateC              extends TestSealedTrait

  // Test enum
  enum TestEnum extends MState:
    case One, Two, Three

  // Test event enum
  enum TestEvent extends MEvent:
    case Click
    case Submit(data: String)

  def spec = suite("SealedEnum Macros")(
    suite("CaseInfo extraction")(
      test("extracts case info from enum") {
        val infos = SealedEnumMacros.extractCaseInfo[TestEnum](CaseHasher.Default)
        assertTrue(
          infos.length == 3,
          infos.map(_.simpleName).toSet == Set("One", "Two", "Three"),
          infos.forall(_.fullName.startsWith("mechanoid.SealedEnumMacroSpec")),
          infos.forall(_.fullName.contains("TestEnum")),
        )
      },
      test("extracts case info from sealed trait") {
        val infos = SealedEnumMacros.extractCaseInfo[TestSealedTrait](CaseHasher.Default)
        assertTrue(
          infos.length == 3,
          infos.map(_.simpleName).toSet == Set("StateA", "StateB", "StateC"),
          infos.forall(_.fullName.startsWith("mechanoid.SealedEnumMacroSpec")),
        )
      },
      test("extracts case info from event enum with case classes") {
        val infos = SealedEnumMacros.extractCaseInfo[TestEvent](CaseHasher.Default)
        assertTrue(
          infos.length == 2,
          infos.map(_.simpleName).toSet == Set("Click", "Submit"),
        )
      },
    ),
    suite("caseHash derivation")(
      test("caseHash is based on full name") {
        val se        = summon[SealedEnum[TestEnum]]
        val oneHash   = se.caseHash(TestEnum.One)
        val twoHash   = se.caseHash(TestEnum.Two)
        val threeHash = se.caseHash(TestEnum.Three)

        // All hashes should be different
        assertTrue(
          oneHash != twoHash,
          twoHash != threeHash,
          oneHash != threeHash,
        )
      },
      test("caseHash is stable - same input gives same hash") {
        val se    = summon[SealedEnum[TestEnum]]
        val hash1 = se.caseHash(TestEnum.One)
        val hash2 = se.caseHash(TestEnum.One)
        assertTrue(hash1 == hash2)
      },
      test("caseHash works for case classes with different data") {
        val se     = summon[SealedEnum[TestSealedTrait]]
        val hashA1 = se.caseHash(StateA(1))
        val hashA2 = se.caseHash(StateA(999))
        val hashB  = se.caseHash(StateB("test"))

        // Same case with different data should have same hash
        assertTrue(
          hashA1 == hashA2,
          hashA1 != hashB,
        )
      },
    ),
    suite("name lookup")(
      test("nameFor returns correct name for hash") {
        val se      = summon[SealedEnum[TestEnum]]
        val oneHash = se.caseHash(TestEnum.One)
        assertTrue(se.nameFor(oneHash) == "One")
      },
      test("nameOf returns correct name for instance") {
        val se = summon[SealedEnum[TestEnum]]
        assertTrue(
          se.nameOf(TestEnum.One) == "One",
          se.nameOf(TestEnum.Two) == "Two",
          se.nameOf(TestEnum.Three) == "Three",
        )
      },
      test("nameFor returns Unknown for invalid hash") {
        val se     = summon[SealedEnum[TestEnum]]
        val result = se.nameFor(999999999)
        assertTrue(result.startsWith("Unknown"))
      },
    ),
    suite("caseInfos")(
      test("caseInfos provides full name information") {
        val se    = summon[SealedEnum[TestEnum]]
        val infos = se.caseInfos
        assertTrue(
          infos.exists(_.simpleName == "One"),
          // Full name contains TestEnum and One (with $ separators in object context)
          infos
            .find(_.simpleName == "One")
            .exists(ci => ci.fullName.contains("TestEnum") && ci.fullName.contains("One")),
        )
      },
      test("allHashes returns all hash values") {
        val se     = summon[SealedEnum[TestEnum]]
        val hashes = se.allHashes
        assertTrue(
          hashes.length == 3,
          hashes.distinct.length == 3, // all unique
        )
      },
    ),
    suite("CaseHasher")(
      test("default hasher uses String.hashCode") {
        // Verify the default hasher produces String.hashCode values
        val se   = summon[SealedEnum[TestEnum]]
        val hash = se.caseHash(TestEnum.One)
        // The hash should be the hashCode of the fully qualified name
        val infos    = se.caseInfos
        val oneInfo  = infos.find(_.simpleName == "One").get
        val expected = oneInfo.fullName.hashCode
        assertTrue(hash == expected)
      },
      test("murmur3 hasher produces different hashes than default") {
        // Default hasher
        val defaultSE = summon[SealedEnum[TestEnum]]

        // Murmur3 hasher - use explicit parameter
        val murmur3SE = SealedEnum.deriveWithHasher[TestEnum](CaseHasher.Murmur3)

        val defaultHash = defaultSE.caseHash(TestEnum.One)
        val murmur3Hash = murmur3SE.caseHash(TestEnum.One)

        // They should produce different values
        assertTrue(defaultHash != murmur3Hash)
      },
      test("murmur3 produces unique hashes for all cases") {
        val se     = SealedEnum.deriveWithHasher[TestEnum](CaseHasher.Murmur3)
        val hashes = se.allHashes

        assertTrue(
          hashes.length == 3,
          hashes.distinct.length == 3, // all unique
        )
      },
    ),
  ) @@ TestAspect.sequential
end SealedEnumMacroSpec
