package io.github.finagle.smtp.util

class TestError extends Error {
  val code = -1
  val info = "test error"
}
