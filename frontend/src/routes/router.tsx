/*
 * 라우트 정의.
 * 경로와 레이아웃 배정은 frontend/docs/screens/frontend-structure.md 3·4장을 따른다.
 * 레이아웃 라우트는 경로가 없는(pathless) 라우트이므로 하위 라우트에 절대 경로를 쓴다.
 *
 * 인증 화면(0.x)은 업무 -302에서 AuthLayout 아래에 추가했다. 인증 **상태**에 따른
 * 라우트 보호는 여전히 범위 밖이다(와이어프레임 이동표 `비로그인 ⇢ 0.6`). 보호 계층이
 * 필요해지면 이 파일에서 레이아웃 라우트를 감싸 처리한다.
 */
import { createBrowserRouter } from 'react-router';
import AppLayout from '../layouts/AppLayout';
import AuthLayout from '../layouts/AuthLayout';
import FullScreenLayout from '../layouts/FullScreenLayout';
import OnboardingLayout from '../layouts/OnboardingLayout';
import AccountFindPage from '../pages/auth/AccountFindPage';
import EmailSignupPage from '../pages/auth/EmailSignupPage';
import LoginPage from '../pages/auth/LoginPage';
import PasswordResetPage from '../pages/auth/PasswordResetPage';
import SignupCompletePage from '../pages/auth/SignupCompletePage';
import SignupMethodPage from '../pages/auth/SignupMethodPage';
import SocialSignupPage from '../pages/auth/SocialSignupPage';
import StartPage from '../pages/auth/StartPage';
import VerifyEmailPage from '../pages/auth/VerifyEmailPage';
import FilmPage from '../pages/film/FilmPage';
import FrameDetailPage from '../pages/film/FrameDetailPage';
import PopcornBucketPage from '../pages/film/PopcornBucketPage';
import HomePage from '../pages/home/HomePage';
import MyDashboardPage from '../pages/my/MyDashboardPage';
import ProfileEditPage from '../pages/my/ProfileEditPage';
import ProfilePreviewPage from '../pages/my/ProfilePreviewPage';
import PublicProfilePage from '../pages/my/PublicProfilePage';
import TasteComparePage from '../pages/my/TasteComparePage';
import OttManagePage from '../pages/otts/OttManagePage';
import MovieDetailPage from '../pages/movies/MovieDetailPage';
import MovieRatingPage from '../pages/movies/MovieRatingPage';
import RatingResultPage from '../pages/movies/RatingResultPage';
import OnboardingCompletePage from '../pages/onboarding/OnboardingCompletePage';
import OttOnboardingPage from '../pages/onboarding/OttOnboardingPage';
import TasteOnboardingPage from '../pages/onboarding/TasteOnboardingPage';
import PartyAnalyzingPage from '../pages/party/PartyAnalyzingPage';
import PartyInvitationPage from '../pages/party/PartyInvitationPage';
import PartyJoinedPage from '../pages/party/PartyJoinedPage';
import PartyListPage from '../pages/party/PartyListPage';
import PartyPopcornMixPage from '../pages/party/PartyPopcornMixPage';
import PartyRecommendationPage from '../pages/party/PartyRecommendationPage';
import PartySetupPage from '../pages/party/PartySetupPage';
import PartySizePage from '../pages/party/PartySizePage';
import PartyPopcornPage from '../pages/party/PartyPopcornPage';
import RecommendationsPage from '../pages/recommendations/RecommendationsPage';
import SearchPage from '../pages/search/SearchPage';
import NotFoundPage from '../pages/system/NotFoundPage';
import NotInScopePage from '../pages/system/NotInScopePage';
import WatchConfirmPage from '../pages/watch/WatchConfirmPage';
import { ROUTE_PATTERN } from './paths';

export const router = createBrowserRouter(
  [
    {
      element: <AuthLayout />,
      children: [
        { path: ROUTE_PATTERN.authStart, element: <StartPage /> },
        { path: ROUTE_PATTERN.signupMethod, element: <SignupMethodPage /> },
        { path: ROUTE_PATTERN.signupSocial, element: <SocialSignupPage /> },
        { path: ROUTE_PATTERN.signupEmail, element: <EmailSignupPage /> },
        { path: ROUTE_PATTERN.signupComplete, element: <SignupCompletePage /> },
        { path: ROUTE_PATTERN.login, element: <LoginPage /> },
        { path: ROUTE_PATTERN.accountFind, element: <AccountFindPage /> },
        { path: ROUTE_PATTERN.passwordReset, element: <PasswordResetPage /> },
        { path: ROUTE_PATTERN.verifyEmail, element: <VerifyEmailPage /> },
      ],
    },
    {
      element: <OnboardingLayout />,
      children: [
        { path: ROUTE_PATTERN.onboardingTaste, element: <TasteOnboardingPage /> },
        { path: ROUTE_PATTERN.onboardingOtt, element: <OttOnboardingPage /> },
        { path: ROUTE_PATTERN.onboardingComplete, element: <OnboardingCompletePage /> },
      ],
    },
    {
      element: <AppLayout />,
      children: [
        { path: ROUTE_PATTERN.home, element: <HomePage /> },
        { path: ROUTE_PATTERN.recommendations, element: <RecommendationsPage /> },
        { path: ROUTE_PATTERN.film, element: <FilmPage /> },
        { path: ROUTE_PATTERN.popcornBucket, element: <PopcornBucketPage /> },
        { path: ROUTE_PATTERN.search, element: <SearchPage /> },
        { path: ROUTE_PATTERN.ottManage, element: <OttManagePage /> },

        // 7.1 MY 대시보드 (업무 -305). 이전에는 NotInScopePage였다
        { path: ROUTE_PATTERN.my, element: <MyDashboardPage /> },

        // 이번 단계 구현 범위가 아닌 탭. REF-01의 5탭 구성을 검증하기 위해 라우트는 둔다.
        { path: ROUTE_PATTERN.party, element: <PartyListPage /> },

        /*
         * 7.1 메뉴가 가리키는 화면 중 306~308 몫(7.3 · 7.5 · 7.6 · 7.8).
         * 경로를 두는 이유는 paths.ts 주석에 적었다 — `아직 안 만든 화면`과
         * `잘못된 주소`가 구분돼야 이동 경로를 검증할 수 있다.
         */
        { path: ROUTE_PATTERN.tasteReports, element: <NotInScopePage /> },
        { path: ROUTE_PATTERN.recommendationHistory, element: <NotInScopePage /> },
        { path: ROUTE_PATTERN.myRatings, element: <NotInScopePage /> },
        { path: ROUTE_PATTERN.settings, element: <NotInScopePage /> },
      ],
    },
    {
      element: <FullScreenLayout />,
      children: [
        { path: ROUTE_PATTERN.movieDetail, element: <MovieDetailPage /> },
        { path: ROUTE_PATTERN.movieRating, element: <MovieRatingPage /> },
        { path: ROUTE_PATTERN.ratingResult, element: <RatingResultPage /> },
        { path: ROUTE_PATTERN.watchConfirm, element: <WatchConfirmPage /> },
        { path: ROUTE_PATTERN.frameDetail, element: <FrameDetailPage /> },
        { path: ROUTE_PATTERN.partyPopcorn, element: <PartyPopcornPage /> },
        { path: ROUTE_PATTERN.partyPopcornMix, element: <PartyPopcornMixPage /> },
        { path: ROUTE_PATTERN.partyNew, element: <PartySizePage /> },
        { path: ROUTE_PATTERN.partySetup, element: <PartySetupPage /> },
        { path: ROUTE_PATTERN.partyAnalyzing, element: <PartyAnalyzingPage /> },
        { path: ROUTE_PATTERN.partyRecommendations, element: <PartyRecommendationPage /> },
        { path: ROUTE_PATTERN.partyInvitation, element: <PartyInvitationPage /> },
        { path: ROUTE_PATTERN.partyJoined, element: <PartyJoinedPage /> },

        // MY 프로필 (업무 -305). 7.1만 탭 화면이고 나머지는 전체화면이다
        { path: ROUTE_PATTERN.profileEdit, element: <ProfileEditPage /> },
        { path: ROUTE_PATTERN.profilePreview, element: <ProfilePreviewPage /> },
        { path: ROUTE_PATTERN.publicProfile, element: <PublicProfilePage /> },
        { path: ROUTE_PATTERN.tasteCompare, element: <TasteComparePage /> },
        { path: ROUTE_PATTERN.notFound, element: <NotFoundPage /> },
      ],
    },
  ],
  { basename: '/app' },
);
