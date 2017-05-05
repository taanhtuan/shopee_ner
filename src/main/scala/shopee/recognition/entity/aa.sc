val a = Seq("jam tangan", "jam tangan pria", "jam")
val b = a.sortWith(_.length > _.length).toArray

a.filterNot(x => a.exists(y => x != y && x.contains(y)))

