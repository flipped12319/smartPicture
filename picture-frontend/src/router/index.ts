import { createRouter, createWebHistory } from 'vue-router'
// import HomeView from '../views/HomeView.vue'
import HomePage from '@/pages/HomePage.vue'
import UserLoginPage from '@/pages/user/UserLoginPage.vue'
import UserRegisterPage from '@/pages/user/UserRegisterPage.vue'
import UserManagePage from '@/pages/admin/UserManagePage.vue'
import AddPicturePage from '@/pages/user/AddPicturePage.vue'
import PictureManagePage from '@/pages/admin/PictureManagePage.vue'
import PictureDetailPage from '@/pages/user/PictureDetailPage.vue'
import EditPicturePage from '@/pages/user/EditPicturePage.vue'
import AddPictureBatchPage from '@/pages/admin/AddPictureBatchPage.vue'
import SpaceManagePage from '@/pages/admin/SpaceManagePage.vue'
import AddSpacePage from '@/pages/user/AddSpacePage.vue'
import MySpacePage from '@/pages/user/MySpacePage.vue'
import SpaceDetailPage from '@/pages/user/SpaceDetailPage.vue'
import MyAlbumPage from '@/pages/user/MyAlbumPage.vue'
import AddAlbumPage from '@/pages/user/AddAlbumPage.vue'
import AlbumDetailPage from '@/pages/user/AlbumDetailPage.vue'
import SpaceMemberPage from '@/pages/user/SpaceMemberPage.vue'
import MyInvitationPage from '@/pages/user/MyInvitationPage.vue'
import ChatPage from '@/pages/ChatPage.vue'
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: '主页',
      component: HomePage,
    },
    {
      path: '/user/login',
      name: '用户登录',
      component: UserLoginPage,
    },
    {
      path: '/user/register',
      name: '用户注册',
      component: UserRegisterPage,
    },
    {
      path: '/admin/userManage',
      name: '用户管理',
      component: UserManagePage,
    },
    {
      path: '/add_picture',
      name: '创建图片',
      component: AddPicturePage,
    },
    {
      path: '/admin/pictureManage',
      name: '图片管理',
      component: PictureManagePage,
    },
    {
      path: '/picture/:id',
      name: '图片详情',
      component: PictureDetailPage,
      props: true,
    },
    {
      path: '/edit_picture/:id',
      name: '编辑图片',
      component: EditPicturePage,
      props: true,
    },
    {
      path: '/add_picture/batch',
      name: '批量创建图片',
      component: AddPictureBatchPage,
    },
    {
      path: '/admin/spaceManage',
      name: '空间管理',
      component: SpaceManagePage,
    },
    {
      path: '/add_space',
      name: '创建空间',
      component: AddSpacePage,
    },
    {
      path: '/my_space',
      name: '我的空间',
      component: MySpacePage,
    },
    {
      path: '/space/:id',
      name: '空间详情',
      component: SpaceDetailPage,
      props: true,
    },
    {
      path: '/space/member/:id',
      name: '空间成员管理',
      component: SpaceMemberPage,
      props: true,
    },
    {
      path: '/my_invitation',
      name: '我的邀请',
      component: MyInvitationPage,
    },
    {
      path: '/my_album',
      name: '我的相册',
      component: MyAlbumPage,
    },
    {
      path: '/add_album',
      name: '创建相册',
      component: AddAlbumPage,
    },
    {
      path: '/album/:id',
      name: '相册详情',
      component: AlbumDetailPage,
      props: true,
    },
    {
      path: '/ai/chat',
      name: '智能助手',
      component: ChatPage,
    },
  ],
})

export default router
