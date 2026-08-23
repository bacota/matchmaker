package com.vivi.tictactoe

/** Writes a failure to stderr, with its cause chain, as one record.
  *
  * Every 500 this engine produces goes through here. `printStackTrace` alone was not enough: it
  * drops the request that failed, and the runtime interleaves its lines with other output, so a
  * trace could not reliably be read back as a unit.
  *
  * `where` names the request that failed. Without it a stack trace in CloudWatch cannot be
  * matched to the call that produced it, which is most of what makes a 500 diagnosable.
  */
object Log {

  def failure(error: Throwable, where: String): Unit = {
    val subject = if (where.isEmpty) "request" else where
    val trace = java.io.StringWriter()
    error.printStackTrace(java.io.PrintWriter(trace))
    System.err.println(s"ERROR handling $subject: ${error.getClass.getName}: ${error.getMessage}\n$trace")
    System.err.flush()
  }
}
