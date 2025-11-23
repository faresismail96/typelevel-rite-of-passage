package com.rite.foundations

object Cats {

  /*
      type classes
      - Applicative --> wrap existing values into containers F[_] (wrapper values) using pure[A](value: A): F[A]
      - Functor --> mappable data structures (map)
      - FlatMap --> extends Functor with flatMap. Allows for for-comprehension
      - Monad --> Applicative + FlatMap
      - ApplicativeError/MonadError --> for computations that can fail. same but with raiseError
      ... other exists... (ApplicativeThrow where the left is a Throwable etc...)
   */
  
}
