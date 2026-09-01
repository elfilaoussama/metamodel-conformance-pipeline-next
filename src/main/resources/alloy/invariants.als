pred ObservationConsistency {}

fun ExclusiveDeclarationOwnershipViolations : set Member {
  { m : Member | not one c : Classifier | m in c.declaredMembers }
}

fun AcyclicGeneralizationViolations : set Classifier {
  { c : Classifier | c in c.^parents }
}

pred localMemberHides[c : Classifier, inherited : Member] {
  some local : c.declaredMembers | sameMemberKey[local, inherited]
}

pred nearerAncestorMemberHides[c, owner : Classifier, inherited : Member] {
  some nearer : c.^parents - owner |
    owner in nearer.^parents and
    some replacement : nearer.declaredMembers |
      replacement.inheritability = INHERITABLE and
      sameMemberKey[replacement, inherited]
}

fun formalInheritedMembers[c : Classifier] : set Member {
  { inherited : Member |
    some owner : c.^parents |
      inherited in owner.declaredMembers and
      inherited.inheritability = INHERITABLE and
      not localMemberHides[c, inherited] and
      not nearerAncestorMemberHides[c, owner, inherited]
  }
}

fun InheritedViewConsistencyViolations : Classifier -> Member {
  { c : Classifier, m : Member |
    (m in c.observedInheritedMembers and m not in formalInheritedMembers[c]) or
    (m not in c.observedInheritedMembers and m in formalInheritedMembers[c])
  }
}

fun LocalInheritedSeparationViolations : Classifier -> Member {
  { c : Classifier, m : Member |
    m in c.declaredMembers and m in c.observedInheritedMembers
  }
}

pred sameMemberKey[m1, m2 : Member] {
  (
    m1.kind = METHOD and
    m2.kind = METHOD and
    m1.memberName = m2.memberName and
    m1.parameterTypeAt = m2.parameterTypeAt
  ) or (
    m1.kind = ATTRIBUTE and
    m2.kind = ATTRIBUTE and
    m1.memberName = m2.memberName
  )
}

fun LocalNamespaceUniquenessViolations : set Member {
  { m1 : Member |
    some c : Classifier |
      m1 in c.declaredMembers and
      some m2 : c.declaredMembers - m1 | sameMemberKey[m1, m2]
  }
}
