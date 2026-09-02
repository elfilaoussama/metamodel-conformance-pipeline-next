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
      memberAccessibleFrom[c, nearer, replacement] and
      sameMemberKey[replacement, inherited]
}

pred samePackageInheritancePath[c, owner : Classifier] {
  c.packageName = owner.packageName and
  owner in c.^({ from, to : Classifier |
    to in from.parents and
    from.packageName = owner.packageName and
    to.packageName = owner.packageName
  })
}

pred memberAccessibleFrom[c, owner : Classifier, member : Member] {
  member.inheritability = INHERITABLE and
  (
    member.visibility = PUBLIC or
    member.visibility = PROTECTED or
    (member.visibility = PACKAGE and samePackageInheritancePath[c, owner])
  )
}

fun formalInheritedMembers[c : Classifier] : set Member {
  { inherited : Member |
    some owner : c.^parents |
      inherited in owner.declaredMembers and
      memberAccessibleFrom[c, owner, inherited] and
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

fun InheritedNamespaceUniquenessViolations : Classifier -> Member {
  { c : Classifier, m1 : formalInheritedMembers[c] |
    some m2 : formalInheritedMembers[c] - m1 | sameMemberKey[m1, m2]
  }
}

fun ImplementationBindingViolations : set univ {
  { m : Member |
    (m.kind = ATTRIBUTE and
      (m.implementationAvailability != IMPLEMENTATION_UNKNOWN or some m.implementationBodies)) or
    (m.kind = METHOD and m.implementationAvailability = IMPLEMENTATION_UNKNOWN) or
    (m.kind = METHOD and m.implementationAvailability = SOURCE_BODY and not one m.implementationBodies) or
    (m.kind = METHOD and m.implementationAvailability = NO_SOURCE_BODY and some m.implementationBodies)
  } +
  { b : MethodBody | not one b.~implementationBodies }
}
